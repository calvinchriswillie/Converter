# HISTORY – PSD WebP Converter

Newest first.

## 1.0.5 – 2026-08-24
- Fix: Python result ok as string "1"/"0" (Kotlin was always reading false)
- Fix: write only to app external files (always writable; public Download blocked on Android 10+)
- Write `_errors.log` in output folder with per-file failure reasons
- Notification shows full output path when done
- versionCode 6

## 1.0.4 – 2026-08-24
- Fix Kotlin continue-in-lambda compile error

## 1.0.3 – 2026-08-24
- Recursive folder, crop visible, corrupt repair

## 1.0.2 – 2026-08-24
- Dropped psd-tools native; Pillow only

## 1.0.0 – 2026-08-24
- Initial scaffold
