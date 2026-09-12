# LAPIS Reader for Android

A small offline Android WebView reader for LAPIS flashcard packs.

## Privacy-first design

The repository contains only the Android reader source code. It does **not** publish the user's 3054-card data set or audio collection.

On first launch, choose the local `LAPIS_Reader_Android_Offline.zip` file. The app extracts only the `reader/` contents into its private app storage and then opens `index.html` completely offline.

Supported ZIP layouts include:

- `LAPIS_Reader_Android/reader/index.html`
- `reader/index.html`
- `index.html` at the ZIP root, with `media/` beside it

The overflow button `⋮` lets you replace the pack later.

## Build

GitHub Actions automatically builds a signed Android debug APK on pushes to `main`, or manually through **Actions → Build LAPIS Reader APK → Run workflow**.

The artifact is named `LAPIS-Reader-Android-APK` and contains:

- `LAPIS-Reader-v1.0-debug.apk`
- `SHA256SUMS.txt`

## Android

- Package: `com.lapis.reader`
- Min SDK: 24 (Android 7.0)
- Target SDK: 35
- Network permission: none
- Storage permission: none; import uses Android's system document picker

## Security notes

ZIP import applies path traversal checks, a 20,000-file limit, and a 300 MiB uncompressed-size limit. Existing packs are replaced only after a new pack has been fully extracted and validated to contain `index.html`.
