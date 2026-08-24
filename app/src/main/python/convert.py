"""
Conversion via Pillow (raster). PSD support pending pure-Python path
(psd-tools needs a C extension that Chaquopy cannot compile).
"""

from __future__ import annotations
import os
from pathlib import Path
from typing import Any, Dict, List

try:
    from PIL import Image
except ImportError:
    Image = None

SUPPORTED_RASTER = {".png", ".jpg", ".jpeg", ".tif", ".tiff", ".bmp", ".gif", ".webp"}
SUPPORTED_PSD = {".psd", ".psb"}


def _ensure_dir(path: str) -> None:
    Path(path).parent.mkdir(parents=True, exist_ok=True)


def convert_raster(
    input_path: str,
    output_path: str,
    fmt: str = "webp",
    quality: int = 80,
    lossless: bool = False,
) -> Dict[str, Any]:
    if Image is None:
        return {"ok": False, "error": "Pillow not available"}

    try:
        with Image.open(input_path) as im:
            if fmt.lower() in ("jpg", "jpeg") and im.mode in ("RGBA", "P", "LA"):
                background = Image.new("RGB", im.size, (255, 255, 255))
                if im.mode == "P":
                    im = im.convert("RGBA")
                background.paste(im, mask=im.split()[-1] if im.mode in ("RGBA", "LA") else None)
                im = background
            elif fmt.lower() == "webp" and im.mode not in ("RGB", "RGBA"):
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

        return {
            "ok": True,
            "input": input_path,
            "output": output_path,
            "size_in": os.path.getsize(input_path),
            "size_out": os.path.getsize(output_path),
        }
    except Exception as e:
        return {"ok": False, "error": str(e), "input": input_path}


def convert_psd(
    input_path: str,
    output_dir: str,
    fmt: str = "webp",
    quality: int = 80,
    lossless: bool = False,
    visible_only: bool = True,
    export_layers: bool = True,
    name_pattern: str = "{stem}_{layer}",
) -> Dict[str, Any]:
    # Placeholder until pure-Python PSD support is vendored
    return {
        "ok": False,
        "error": "PSD support not yet available (psd-tools needs native code). Raster conversion works.",
        "input": input_path,
    }


def process_file(
    input_path: str,
    output_dir: str,
    fmt: str = "webp",
    quality: int = 80,
    lossless: bool = False,
    visible_only: bool = True,
    export_layers: bool = True,
) -> Dict[str, Any]:
    ext = Path(input_path).suffix.lower()
    if ext in SUPPORTED_PSD:
        return convert_psd(
            input_path, output_dir, fmt, quality, lossless,
            visible_only=visible_only, export_layers=export_layers
        )
    out_name = Path(input_path).stem + f".{fmt}"
    output_path = str(Path(output_dir) / out_name)
    return convert_raster(input_path, output_path, fmt, quality, lossless)
