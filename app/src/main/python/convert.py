"""
Robust raster conversion with repair for partial/corrupt images.
Mirrors the intent of the Termux batch script + cwebp/ImageMagick tiers.
"""

from __future__ import annotations
import os
from pathlib import Path
from typing import Any, Dict, Optional, Tuple

try:
    from PIL import Image, ImageFile
    # Allow loading truncated/partial images (tier-2 style recovery)
    ImageFile.LOAD_TRUNCATED_IMAGES = True
except ImportError:
    Image = None
    ImageFile = None


SUPPORTED_RASTER = {".png", ".jpg", ".jpeg", ".tif", ".tiff", ".bmp", ".gif", ".webp"}
SUPPORTED_PSD = {".psd", ".psb"}


def _ensure_dir(path: str) -> None:
    Path(path).parent.mkdir(parents=True, exist_ok=True)


def _crop_visible(im: "Image.Image") -> "Image.Image":
    """Crop to non-empty / non-transparent bounding box when possible."""
    try:
        if im.mode in ("RGBA", "LA"):
            alpha = im.split()[-1]
            bbox = alpha.getbbox()
            if bbox:
                return im.crop(bbox)
        # For RGB/others: trim near-white/black uniform borders is harder;
        # try getbbox on a luminance mask for sparse images
        if im.mode != "RGB":
            tmp = im.convert("RGB")
        else:
            tmp = im
        # Simple: if image has transparency-like empty after convert
        bbox = tmp.getbbox()
        if bbox and bbox != (0, 0, im.width, im.height):
            return im.crop(bbox)
    except Exception:
        pass
    return im


def _try_open(path: str) -> Tuple[Optional["Image.Image"], str]:
    """
    Open image with recovery tiers:
      1. Normal open
      2. Truncated load (LOAD_TRUNCATED_IMAGES already on)
      3. Force RGB re-load via draft if available
    Returns (image_or_None, note)
    """
    if Image is None:
        return None, "Pillow missing"

    notes = []
    try:
        im = Image.open(path)
        im.load()  # force decode now
        return im, "ok"
    except Exception as e1:
        notes.append(f"open1:{e1}")

    try:
        im = Image.open(path)
        # partial load
        im.load()
        return im, "truncated_ok"
    except Exception as e2:
        notes.append(f"open2:{e2}")

    try:
        # Last resort: open without full load, copy what we can
        im = Image.open(path)
        im = im.copy()
        return im, "copy_partial"
    except Exception as e3:
        notes.append(f"open3:{e3}")
        return None, "; ".join(notes)


def convert_raster(
    input_path: str,
    output_path: str,
    fmt: str = "webp",
    quality: int = 80,
    lossless: bool = False,
    crop_visible: bool = True,
) -> Dict[str, Any]:
    if Image is None:
        return {"ok": False, "error": "Pillow not available"}

    im, note = _try_open(input_path)
    if im is None:
        return {"ok": False, "error": f"unreadable: {note}", "input": input_path, "repaired": False}

    repaired = note != "ok"
    try:
        if crop_visible:
            im = _crop_visible(im)

        # Mode fix for target format
        fmt_l = fmt.lower()
        if fmt_l in ("jpg", "jpeg") and im.mode in ("RGBA", "P", "LA"):
            bg = Image.new("RGB", im.size, (255, 255, 255))
            if im.mode == "P":
                im = im.convert("RGBA")
            mask = im.split()[-1] if im.mode in ("RGBA", "LA") else None
            bg.paste(im, mask=mask)
            im = bg
        elif fmt_l == "webp" and im.mode not in ("RGB", "RGBA"):
            im = im.convert("RGBA" if "A" in im.mode else "RGB")

        save_kwargs = {}
        fmt_upper = fmt.upper()
        if fmt_upper in ("JPEG", "JPG"):
            fmt_upper = "JPEG"
            save_kwargs["quality"] = quality
            save_kwargs["optimize"] = True
        elif fmt_upper == "WEBP":
            save_kwargs["quality"] = quality
            save_kwargs["lossless"] = lossless
            save_kwargs["method"] = 4
        elif fmt_upper == "PNG":
            save_kwargs["optimize"] = True

        _ensure_dir(output_path)
        im.save(output_path, format=fmt_upper, **save_kwargs)
        im.close()

        return {
            "ok": True,
            "input": input_path,
            "output": output_path,
            "repaired": repaired,
            "note": note,
            "size_in": os.path.getsize(input_path) if os.path.exists(input_path) else 0,
            "size_out": os.path.getsize(output_path),
        }
    except Exception as e:
        try:
            im.close()
        except Exception:
            pass
        return {"ok": False, "error": str(e), "input": input_path, "repaired": repaired}


def convert_psd(*args, **kwargs) -> Dict[str, Any]:
    return {
        "ok": False,
        "error": "PSD not available yet (needs pure-Python path). Raster + repair works.",
    }


def process_file(
    input_path: str,
    output_path: str,
    fmt: str = "webp",
    quality: int = 80,
    lossless: bool = False,
    visible_only: bool = True,
    export_layers: bool = True,
    crop_visible: bool = True,
) -> Dict[str, Any]:
    """
    output_path is the full destination file path (not just a directory).
    """
    ext = Path(input_path).suffix.lower()
    if ext in SUPPORTED_PSD:
        return convert_psd()
    return convert_raster(
        input_path, output_path, fmt, quality, lossless, crop_visible=crop_visible
    )
