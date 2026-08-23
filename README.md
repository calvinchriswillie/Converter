# PSD WebP Converter

Standalone Android APK that replicates the core behaviour of **cwebp** + **psd-tools** (as used in Termux).

**Package name (fixed forever):** `com.convert.psdwebp`

## Features
- SAF file / folder picker + share / view intents (works with MiXplorer and other apps)
- Raster conversion via Pillow (quality slider + lossless toggle)
- PSD/PSB support via psd-tools (composite + optional layer export, visible-only toggle)
- Selectable output format (default: webp) – webp / png / jpg / tiff / bmp
- Background foreground service with notification showing processed count + overall %
- Newer builds install over older ones (same package + same keystore)

## Build on GitHub
- Workflow: `.github/workflows/build.yml`
- Artifact retention: **1 day**
- Signing: put your keystore in GitHub Secrets (see below)

### Required GitHub Secrets
| Secret name          | Description                          |
|----------------------|--------------------------------------|
| `KEYSTORE_BASE64`    | `base64 -w0 keystore.jks` output     |
| `KEYSTORE_PASSWORD`  | store password                       |
| `KEY_ALIAS`          | key alias                            |
| `KEY_PASSWORD`       | key password                         |

Generate a keystore locally once:
```bash
keytool -genkey -v -keystore keystore.jks -keyalg RSA -keysize 2048 -validity 10000 -alias convert
# then:
base64 -w0 keystore.jks   # paste into KEYSTORE_BASE64 secret
```

Keep the original `keystore.jks` safe – you need it for every future update.

## Quick install notes (put in every release)
```bash
# After downloading the APK from the release:
adb install -r app-release.apk

# Or from Termux (after copying to shared storage):
termux-open ~/storage/downloads/app-release.apk
```

## Manual iteration
After the first successful APK you will tweak options, UI, output location, cwebp binary fallback, etc. one by one. All changes go into `HISTORY.md`.

## Project status
Initial scaffold – functional skeleton with Chaquopy, Python conversion script, SAF picker, service + progress notification, and CI. Ready for first build and then incremental improvements.
