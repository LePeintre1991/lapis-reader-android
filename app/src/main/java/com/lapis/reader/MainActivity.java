package com.lapis.reader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class MainActivity extends Activity {
    private static final int REQUEST_PACK = 4107;
    private static final long MAX_UNCOMPRESSED_BYTES = 350L * 1024L * 1024L;
    private static final int MAX_FILES = 25000;

    private FrameLayout root;
    private WebView webView;
    private File readerDir;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(16, 38, 63));
        getWindow().setNavigationBarColor(Color.rgb(243, 248, 250));
        readerDir = new File(getFilesDir(), "reader");
        if (new File(readerDir, "index.html").isFile()) showReader();
        else showWelcome();
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private TextView text(String value, float sp, int color) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(sp);
        v.setTextColor(color);
        return v;
    }

    private void showWelcome() {
        if (webView != null) { webView.destroy(); webView = null; }
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(243, 248, 250));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.setPadding(dp(28), dp(28), dp(28), dp(28));

        TextView mark = text("LAPIS GLACIER", 12, Color.rgb(52, 123, 139));
        mark.setLetterSpacing(0.18f);
        panel.addView(mark);

        TextView title = text("LAPIS Reader", 31, Color.rgb(16, 38, 63));
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.topMargin = dp(10);
        panel.addView(title, titleLp);

        TextView desc = text("离线闪卡阅读器 v1.1\n可直接选择 Anki .apkg、包含 .apkg 的 ZIP，\n也兼容 LAPIS HTML 离线卡包。\n卡片和音频只保存在本机。", 15, Color.rgb(40, 54, 64));
        desc.setGravity(Gravity.CENTER);
        desc.setLineSpacing(0f, 1.42f);
        LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        descLp.topMargin = dp(18);
        panel.addView(desc, descLp);

        Button importButton = new Button(this);
        importButton.setText("选择 Anki / ZIP 卡包");
        importButton.setTextSize(15);
        importButton.setTextColor(Color.WHITE);
        importButton.setBackgroundColor(Color.rgb(23, 63, 115));
        importButton.setOnClickListener(v -> choosePack());
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        btnLp.topMargin = dp(26);
        panel.addView(importButton, btnLp);

        TextView hint = text("你可以直接选择原来的 English3054 Anki 压缩包；如果 ZIP 内再套一层 .apkg，v1.1 会自动找到并读取。", 12, Color.rgb(82, 97, 109));
        hint.setGravity(Gravity.CENTER);
        hint.setLineSpacing(0f, 1.35f);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.topMargin = dp(18);
        panel.addView(hint, hintLp);

        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        panelLp.leftMargin = dp(12);
        panelLp.rightMargin = dp(12);
        root.addView(panel, panelLp);
        setContentView(root);
    }

    private void showReader() {
        root = new FrameLayout(this);
        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(243, 248, 250));
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setLoadWithOverviewMode(false);
        s.setUseWideViewPort(false);
        webView.setWebViewClient(new WebViewClient());
        root.addView(webView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView menu = text("⋮", 27, Color.WHITE);
        menu.setGravity(Gravity.CENTER);
        menu.setBackgroundColor(Color.argb(220, 16, 38, 63));
        menu.setContentDescription("LAPIS Reader 菜单");
        menu.setOnClickListener(v -> showReaderMenu());
        FrameLayout.LayoutParams menuLp = new FrameLayout.LayoutParams(dp(42), dp(42), Gravity.TOP | Gravity.END);
        menuLp.topMargin = dp(9);
        menuLp.rightMargin = dp(9);
        root.addView(menu, menuLp);

        setContentView(root);
        webView.loadUrl(Uri.fromFile(new File(readerDir, "index.html")).toString());
    }

    private void showReaderMenu() {
        new AlertDialog.Builder(this).setTitle("LAPIS Reader")
                .setItems(new String[]{"导入 / 替换卡包", "刷新当前卡包", "关于"}, (dialog, which) -> {
                    if (which == 0) choosePack();
                    else if (which == 1 && webView != null) webView.reload();
                    else if (which == 2) showAbout();
                }).show();
    }

    private void showAbout() {
        new AlertDialog.Builder(this).setTitle("LAPIS Reader 1.1")
                .setMessage("支持：\n• 直接选择 Anki .apkg\n• ZIP 内嵌 .apkg\n• LAPIS HTML 离线 ZIP\n\n对于 English3054 牌组，会读取 Anki SQLite 字段、媒体映射和原音频，并在本机生成离线阅读界面。内容不会上传。")
                .setPositiveButton("确定", null).show();
    }

    private void choosePack() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/x-zip-compressed", "application/octet-stream", "application/x-apkg", "*/*"});
        startActivityForResult(intent, REQUEST_PACK);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PACK && resultCode == RESULT_OK && data != null && data.getData() != null) importPack(data.getData());
    }

    private void importPack(Uri uri) {
        final AlertDialog progress = makeProgressDialog();
        progress.show();
        new Thread(() -> {
            File selected = new File(getFilesDir(), "selected_pack.tmp");
            File tmp = new File(getFilesDir(), "reader_import_tmp");
            try {
                deleteRecursively(selected);
                deleteRecursively(tmp);
                copyUriToFile(uri, selected);

                boolean importedAsAnki = AnkiPackImporter.importIfAnki(this, selected, tmp);
                if (!importedAsAnki) {
                    deleteRecursively(tmp);
                    if (!tmp.mkdirs() && !tmp.isDirectory()) throw new IOException("无法创建临时目录");
                    try (InputStream raw = new BufferedInputStream(new FileInputStream(selected))) { extractReaderZip(raw, tmp); }
                }

                File index = new File(tmp, "index.html");
                if (!index.isFile()) throw new IOException("没有找到可读取的 Anki 数据或 HTML。支持 .apkg、包含 .apkg 的 ZIP、或包含 reader/index.html 的 LAPIS 卡包。\n\n" + summarizeZip(selected));

                File backup = new File(getFilesDir(), "reader_backup");
                deleteRecursively(backup);
                if (readerDir.exists() && !readerDir.renameTo(backup)) throw new IOException("无法备份旧卡包");
                if (!tmp.renameTo(readerDir)) {
                    if (backup.exists()) backup.renameTo(readerDir);
                    throw new IOException("无法启用新卡包");
                }
                deleteRecursively(backup);
                deleteRecursively(selected);

                runOnUiThread(() -> { progress.dismiss(); Toast.makeText(this, "卡包导入完成", Toast.LENGTH_SHORT).show(); showReader(); });
            } catch (Exception e) {
                deleteRecursively(tmp);
                deleteRecursively(selected);
                runOnUiThread(() -> { progress.dismiss(); new AlertDialog.Builder(this).setTitle("导入失败").setMessage(e.getMessage() == null ? e.toString() : e.getMessage()).setPositiveButton("确定", null).show(); });
            }
        }, "lapis-pack-import").start();
    }

    private void copyUriToFile(Uri uri, File output) throws IOException {
        long total = 0;
        byte[] buffer = new byte[32 * 1024];
        InputStream source = getContentResolver().openInputStream(uri);
        if (source == null) throw new IOException("无法读取所选文件");
        try (InputStream in = new BufferedInputStream(source); BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(output))) {
            int n;
            while ((n = in.read(buffer)) > 0) {
                total += n;
                if (total > MAX_UNCOMPRESSED_BYTES) throw new IOException("所选文件过大");
                out.write(buffer, 0, n);
            }
        }
    }

    private AlertDialog makeProgressDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(24), dp(20), dp(24), dp(20));
        box.setGravity(Gravity.CENTER_VERTICAL);
        ProgressBar bar = new ProgressBar(this);
        box.addView(bar, new LinearLayout.LayoutParams(dp(32), dp(32)));
        TextView t = text("正在识别并导入卡包…", 15, Color.rgb(40, 54, 64));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(16);
        box.addView(t, lp);
        AlertDialog d = new AlertDialog.Builder(this).setView(box).setCancelable(false).create();
        d.setCanceledOnTouchOutside(false);
        return d;
    }

    private void extractReaderZip(InputStream input, File outDir) throws IOException {
        int fileCount = 0;
        long totalBytes = 0;
        byte[] buffer = new byte[32 * 1024];
        String outCanonical = outDir.getCanonicalPath() + File.separator;
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(input))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String relative = normalizeReaderPath(entry.getName());
                if (relative == null || relative.isEmpty()) { zis.closeEntry(); continue; }
                File target = new File(outDir, relative);
                if (!target.getCanonicalPath().startsWith(outCanonical)) throw new IOException("ZIP 路径不安全");
                if (entry.isDirectory()) {
                    if (!target.mkdirs() && !target.isDirectory()) throw new IOException("无法创建目录: " + relative);
                } else {
                    fileCount++;
                    if (fileCount > MAX_FILES) throw new IOException("ZIP 文件数量过多");
                    File parent = target.getParentFile();
                    if (parent != null && !parent.mkdirs() && !parent.isDirectory()) throw new IOException("无法创建目录");
                    try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(target))) {
                        int n;
                        while ((n = zis.read(buffer)) > 0) {
                            totalBytes += n;
                            if (totalBytes > MAX_UNCOMPRESSED_BYTES) throw new IOException("ZIP 解压后体积过大");
                            out.write(buffer, 0, n);
                        }
                    }
                }
                zis.closeEntry();
            }
        }
    }

    private String normalizeReaderPath(String raw) {
        if (raw == null) return null;
        String name = raw.replace('\\', '/');
        while (name.startsWith("/")) name = name.substring(1);
        if (name.contains("../") || name.equals("..") || name.contains("/..")) return null;
        int marker = name.indexOf("/reader/");
        if (marker >= 0) return name.substring(marker + "/reader/".length());
        if (name.startsWith("reader/")) return name.substring("reader/".length());
        if (name.equals("reader")) return null;
        if (name.equals("index.html") || name.equals("front.jpg") || name.equals("ice_mist_back.jpg") || name.startsWith("media/")) return name;
        return null;
    }

    private String summarizeZip(File file) {
        List<String> names = new ArrayList<>();
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(file)) {
            java.util.Enumeration<? extends ZipEntry> e = zip.entries();
            while (e.hasMoreElements() && names.size() < 8) {
                ZipEntry z = e.nextElement();
                if (!z.isDirectory()) names.add(z.getName());
            }
        } catch (Exception ignored) { return "所选文件不是可识别的 ZIP / APKG。"; }
        return "文件里看到的前几个条目：\n• " + android.text.TextUtils.join("\n• ", names);
    }

    private void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        file.delete();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) { webView.destroy(); webView = null; }
        super.onDestroy();
    }
}
