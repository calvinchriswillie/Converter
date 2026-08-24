# HISTORY – PSD WebP Converter

All changes are recorded here. Newest first.

## 1.0.2 – 2026-08-24
- Fix: remove psd-tools (has C++ extension; Chaquopy cannot compile native code)
- Raster conversion only for now (Pillow + numpy)
- PSD returns a clear error until pure-Python path is added
- versionCode 3

## 1.0.1 – 2026-08-24
- Attempted psd-tools --no-deps; still failed on native _rle.cpp
- ABI arm64-v8a only

## 1.0.0 – 2026-08-24
- Initial scaffold, install-over keystore, build on push
