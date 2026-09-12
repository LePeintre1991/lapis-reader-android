package com.lapis.reader;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class AnkiPackImporter {
    private static final long MAX_COPY_BYTES = 350L * 1024L * 1024L;
    private static final int BUFFER = 32 * 1024;
    private static final Pattern SOUND = Pattern.compile("\\[sound:([^\\]]+)\\]");

    private AnkiPackImporter() {}

    static boolean importIfAnki(Context context, File selectedArchive, File outDir) throws Exception {
        File working = new File(context.getFilesDir(), "anki_import_work");
        deleteRecursively(working);
        if (!working.mkdirs() && !working.isDirectory()) throw new IOException("无法创建 Anki 导入临时目录");

        File apkg = locateApkg(selectedArchive, working);
        if (apkg == null) {
            deleteRecursively(working);
            return false;
        }

        try {
            convertApkg(context, apkg, outDir, working);
            return true;
        } finally {
            deleteRecursively(working);
        }
    }

    private static File locateApkg(File selectedArchive, File working) throws IOException {
        try (ZipFile zip = new ZipFile(selectedArchive)) {
            if (hasCollection(zip) && zip.getEntry("media") != null) return selectedArchive;

            ZipEntry best = null;
            for (java.util.Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements();) {
                ZipEntry entry = e.nextElement();
                if (entry.isDirectory()) continue;
                if (entry.getName().toLowerCase().endsWith(".apkg")) {
                    best = entry;
                    break;
                }
            }
            if (best == null) return null;

            File nested = new File(working, "nested.apkg");
            try (InputStream in = new BufferedInputStream(zip.getInputStream(best));
                 BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(nested))) {
                copyLimited(in, out, MAX_COPY_BYTES);
            }
            return nested;
        } catch (java.util.zip.ZipException badZip) {
            return null;
        }
    }

    private static boolean hasCollection(ZipFile zip) {
        return zip.getEntry("collection.anki2") != null || zip.getEntry("collection.anki21") != null;
    }

    private static void convertApkg(Context context, File apkg, File outDir, File working) throws Exception {
        deleteRecursively(outDir);
        if (!outDir.mkdirs() && !outDir.isDirectory()) throw new IOException("无法创建阅读器目录");
        File mediaDir = new File(outDir, "media");
        if (!mediaDir.mkdirs() && !mediaDir.isDirectory()) throw new IOException("无法创建媒体目录");

        File dbFile = new File(working, "collection.db");
        String frontMediaName = null;
        String backMediaName = null;

        try (ZipFile zip = new ZipFile(apkg)) {
            ZipEntry collection = zip.getEntry("collection.anki2");
            if (collection == null) collection = zip.getEntry("collection.anki21");
            if (collection == null) throw new IOException("APKG 中没有找到 collection.anki2 / collection.anki21");
            try (InputStream in = new BufferedInputStream(zip.getInputStream(collection));
                 BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(dbFile))) {
                copyLimited(in, out, MAX_COPY_BYTES);
            }

            ZipEntry mediaEntry = zip.getEntry("media");
            if (mediaEntry == null) throw new IOException("APKG 中没有找到 media 映射");
            String mediaJson;
            try (InputStream in = zip.getInputStream(mediaEntry)) {
                mediaJson = readUtf8(in, 12 * 1024 * 1024);
            }
            JSONObject mediaMap = new JSONObject(mediaJson);
            Iterator<String> keys = mediaMap.keys();
            long totalMedia = 0;
            while (keys.hasNext()) {
                String key = keys.next();
                String fileName = mediaMap.optString(key, "");
                if (fileName.isEmpty()) continue;
                ZipEntry mediaZipEntry = zip.getEntry(key);
                if (mediaZipEntry == null || mediaZipEntry.isDirectory()) continue;

                File target = safeMediaTarget(mediaDir, fileName);
                File parent = target.getParentFile();
                if (parent != null && !parent.mkdirs() && !parent.isDirectory()) throw new IOException("无法创建媒体子目录");
                try (InputStream in = new BufferedInputStream(zip.getInputStream(mediaZipEntry));
                     BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(target))) {
                    totalMedia += copyLimited(in, out, MAX_COPY_BYTES - totalMedia);
                }
                if (totalMedia > MAX_COPY_BYTES) throw new IOException("Anki 媒体解压后体积过大");

                String lower = fileName.toLowerCase();
                if (frontMediaName == null && (lower.contains("_lapis_glacier_anki_front") || lower.equals("cover_background.png"))) {
                    frontMediaName = fileName;
                }
                if (lower.contains("ice_mist") && (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png"))) {
                    backMediaName = fileName;
                } else if (backMediaName == null && lower.equals("body_background.png")) {
                    backMediaName = fileName;
                }
            }
        }

        if (frontMediaName != null) copyFile(safeMediaTarget(mediaDir, frontMediaName), new File(outDir, "front.jpg"));
        if (backMediaName != null) copyFile(safeMediaTarget(mediaDir, backMediaName), new File(outDir, "ice_mist_back.jpg"));

        String notesJson = buildNotesJson(dbFile);
        String template = readAsset(context, "reader_template.html");
        if (!template.contains("__NOTES_JSON__")) throw new IOException("阅读器模板缺少数据占位符");
        String html = template.replace("__NOTES_JSON__", notesJson);
        try (FileOutputStream out = new FileOutputStream(new File(outDir, "index.html"))) {
            out.write(html.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String buildNotesJson(File dbFile) throws Exception {
        SQLiteDatabase db = null;
        Cursor col = null;
        Cursor notes = null;
        try {
            db = SQLiteDatabase.openDatabase(dbFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
            col = db.rawQuery("SELECT models FROM col LIMIT 1", null);
            if (!col.moveToFirst()) throw new IOException("Anki 数据库中没有模型信息");
            JSONObject models = new JSONObject(col.getString(0));
            Map<Long, List<String>> fieldsByModel = parseFieldNames(models);

            notes = db.rawQuery("SELECT id, mid, flds FROM notes ORDER BY id", null);
            StringBuilder out = new StringBuilder(Math.max(1024 * 1024, notes.getCount() * 2048));
            out.append('[');
            boolean first = true;
            while (notes.moveToNext()) {
                long id = notes.getLong(0);
                long mid = notes.getLong(1);
                String flds = notes.getString(2);
                List<String> names = fieldsByModel.get(mid);
                if (names == null) continue;
                String[] values = flds == null ? new String[0] : flds.split(String.valueOf((char) 31), -1);
                Map<String, String> f = new HashMap<>();
                for (int i = 0; i < names.size(); i++) f.put(names.get(i), i < values.length ? values[i] : "");

                String displayWord = firstNonEmpty(f.get("DisplayWord"), f.get("Word"));
                if (displayWord.isEmpty()) displayWord = firstNonEmpty(f.get("Front"), "(无标题)");
                String word = firstNonEmpty(f.get("Word"), stripHtml(displayWord));
                String audio = extractAudioName(f.get("Audio"));

                if (!first) out.append(',');
                first = false;
                out.append('{');
                add(out, "id", String.valueOf(id), true);
                add(out, "w", displayWord, false);
                add(out, "word", word, false);
                add(out, "ipa", f.get("IPA"), false);
                add(out, "pos", f.get("PartsOfSpeech"), false);
                add(out, "grammar", f.get("Grammar"), false);
                add(out, "promptZH", f.get("PromptZH"), false);
                add(out, "promptEN", f.get("PromptEN"), false);
                add(out, "coreZH", f.get("CoreZH"), false);
                add(out, "recall", firstNonEmpty(f.get("RecognitionZH"), f.get("CoreZH")), false);
                add(out, "eng", f.get("EnglishSenses"), false);
                add(out, "coll", f.get("Collocations"), false);
                add(out, "comp", f.get("Comparison"), false);
                add(out, "zh", f.get("ChineseSenses"), false);
                add(out, "root", f.get("RootMemory"), false);
                add(out, "inventory", f.get("SenseInventory"), false);
                add(out, "usage", f.get("UsageNotes"), false);
                add(out, "pron", f.get("PronunciationGuide"), false);
                add(out, "audio", audio, false);
                out.append('}');
            }
            out.append(']');
            if (first) throw new IOException("Anki 数据库中没有可读取的笔记");
            return out.toString();
        } finally {
            if (notes != null) notes.close();
            if (col != null) col.close();
            if (db != null) db.close();
        }
    }

    private static Map<Long, List<String>> parseFieldNames(JSONObject models) throws JSONException {
        Map<Long, List<String>> result = new HashMap<>();
        Iterator<String> keys = models.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            JSONObject model = models.getJSONObject(key);
            JSONArray flds = model.getJSONArray("flds");
            List<FieldOrd> ordered = new ArrayList<>();
            for (int i = 0; i < flds.length(); i++) {
                JSONObject f = flds.getJSONObject(i);
                ordered.add(new FieldOrd(f.optInt("ord", i), f.optString("name", "Field" + i)));
            }
            Collections.sort(ordered, Comparator.comparingInt(a -> a.ord));
            List<String> names = new ArrayList<>();
            for (FieldOrd item : ordered) names.add(item.name);
            result.put(Long.parseLong(key), names);
        }
        return result;
    }

    private static final class FieldOrd {
        final int ord;
        final String name;
        FieldOrd(int ord, String name) { this.ord = ord; this.name = name; }
    }

    private static void add(StringBuilder b, String key, String value, boolean first) {
        if (!first) b.append(',');
        b.append(JSONObject.quote(key)).append(':').append(JSONObject.quote(value == null ? "" : value));
    }

    private static String extractAudioName(String value) {
        if (value == null) return "";
        Matcher m = SOUND.matcher(value);
        if (m.find()) return m.group(1);
        String v = stripHtml(value).trim();
        if (v.toLowerCase().matches(".*\\.(mp3|wav|ogg|m4a)$")) return v;
        return "";
    }

    private static String stripHtml(String value) {
        if (value == null) return "";
        return value.replaceAll("<[^>]+>", " ").replace("&nbsp;", " ").trim();
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) if (v != null && !v.trim().isEmpty()) return v;
        return "";
    }

    private static File safeMediaTarget(File root, String fileName) throws IOException {
        String name = fileName.replace('\\', '/');
        while (name.startsWith("/")) name = name.substring(1);
        if (name.contains("../") || name.equals("..") || name.contains("/..")) throw new IOException("Anki 媒体路径不安全");
        File target = new File(root, name);
        String rootCanonical = root.getCanonicalPath() + File.separator;
        if (!target.getCanonicalPath().startsWith(rootCanonical)) throw new IOException("Anki 媒体路径不安全");
        return target;
    }

    private static long copyLimited(InputStream in, BufferedOutputStream out, long remaining) throws IOException {
        if (remaining <= 0) throw new IOException("导入内容体积过大");
        byte[] buffer = new byte[BUFFER];
        long total = 0;
        int n;
        while ((n = in.read(buffer)) > 0) {
            total += n;
            if (total > remaining) throw new IOException("导入内容体积过大");
            out.write(buffer, 0, n);
        }
        return total;
    }

    private static String readUtf8(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) {
            if (out.size() + n > limit) throw new IOException("文本条目过大");
            out.write(buffer, 0, n);
        }
        return out.toString("UTF-8");
    }

    private static String readAsset(Context context, String name) throws IOException {
        try (InputStream in = context.getAssets().open(name)) {
            return readUtf8(in, 2 * 1024 * 1024);
        }
    }

    private static void copyFile(File src, File dst) throws IOException {
        if (src == null || !src.isFile()) return;
        try (InputStream in = new BufferedInputStream(new FileInputStream(src));
             BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(dst))) {
            copyLimited(in, out, MAX_COPY_BYTES);
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        file.delete();
    }
}
