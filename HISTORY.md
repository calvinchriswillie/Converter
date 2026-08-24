# HISTORY – PSD WebP Converter

All changes are recorded here. Newest first.

## 1.0.1 – 2026-08-24
- Fix Chaquopy build: install psd-tools with `--no-deps` (avoids scikit-image/meson failure)
- Keep only Pillow + numpy (Chaquopy wheels) + attrs/packaging
- ABI reduced to arm64-v8a for faster first builds
- versionCode 2

## 1.0.0 – 2026-08-24
- Package name fixed: `com.convert.psdwebp`
- Chaquopy + psd-tools + Pillow for conversion
- Basic options: quality slider, lossless toggle, PSD visible-only / export-layers toggles
- Output format selectable (default webp)
- SAF file/folder picker + share/view intent support (MiXplorer compatible)
- Foreground service + progress notification (count + %)
- GitHub Actions: builds on every push, artifact retention-days = 1
- **Install-over**: committed `app/psdwebp.keystore` used for both debug + release (same pattern as refboard)
- Python conversion script with raster + PSD composite/layer export
