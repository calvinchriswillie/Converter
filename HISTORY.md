# HISTORY – PSD WebP Converter

All changes are recorded here. Newest first.

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
- Ready for manual iteration after first APK
