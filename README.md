# PSD WebP Converter

Standalone Android APK that replicates core behaviour of **cwebp** + **psd-tools** (as used in Termux).

**Package name (fixed forever):** `com.convert.psdwebp`

## Install-over behaviour
Same applicationId + same signing key for every build.  
Keystore is committed: `app/psdwebp.keystore`  
(store/key password: `android`, alias: `convert`)  

New APKs always install over older ones (same approach as the refboard project).

## Features
- SAF file / folder picker + share / view intents (works with MiXplorer)
- Raster conversion via Pillow (quality slider + lossless toggle)
- PSD/PSB via psd-tools (composite + optional layer export, visible-only toggle)
- Selectable output format (default: webp)
- Background foreground service + notification (`N / total · X%`)

## Build on GitHub
- Triggers on every push to `main` / `master`
- Builds debug APK (already signed with the shared keystore)
- Artifact retention: **1 day**
- No secrets required

## Quick install (Termux / adb)
```bash
adb install -r app-debug.apk
# or from Termux after download:
termux-open ~/storage/downloads/app-debug.apk
```

## Manual iteration
After the first successful APK, change one thing at a time and append every change to `HISTORY.md`.
