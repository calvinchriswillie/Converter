"""
Core conversion logic using psd-tools + Pillow.
Called from Kotlin via Chaquopy.
Supports:
- Raster images → any Pillow-supported format (default webp)
- PSD/PSB → composite + optional per-layer export
"""

from __future__ import annotations
import os
from pathlib import Path
from typing import Optional, List, Dict, Any

try:
    from PIL import Image
except ImportError:
    Image = None

try:
    from psd_tools import PSDImage
except ImportError:
    PSDImage = None


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
    """Convert a single raster image with Pillow."""
    if Image is None:
        return {"ok": False, "error": "Pillow not available"}

    try:
        with Image.open(input_path) as im:
            # Convert mode if needed for the target format
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
    """
    Convert PSD/PSB.
    - Always writes a flattened composite.
    - Optionally exports individual layers (visible_only controls which).
    """
    if PSDImage is None or Image is None:
        return {"ok": False, "error": "psd-tools or Pillow not available"}

    results: List[Dict[str, Any]] = []
    try:
        psd = PSDImage.open(input_path)
        stem = Path(input_path).stem
        out_dir = Path(output_dir)
        out_dir.mkdir(parents=True, exist_ok=True)

        # 1. Flattened composite
        composite = psd.composite()
        if composite is not None:
            comp_path = str(out_dir / f"{stem}_composite.{fmt}")
            r = _save_pil(composite, comp_path, fmt, quality, lossless)
            results.append(r)

        # 2. Individual layers
        if export_layers:
            for i, layer in enumerate(psd):
                if visible_only and not layer.is_visible():
                    continue
                try:
                    layer_img = layer.composite()
                    if layer_img is None:
                        continue
                    safe_name = "".join(c if c.isalnum() or c in "-_ " else "_" for c in (layer.name or f"layer{i}"))
                    layer_name = name_pattern.format(stem=stem, layer=safe_name, index=i)
                    layer_path = str(out_dir / f"{layer_name}.{fmt}")
                    r = _save_pil(layer_img, layer_path, fmt, quality, lossless)
                    results.append(r)
                except Exception as le:
                    results.append({"ok": False, "error": f"layer {i}: {le}"})

        ok_count = sum(1 for r in results if r.get("ok"))
        return {
            "ok": ok_count > 0,
            "input": input_path,
            "outputs": results,
            "layers_exported": ok_count,
        }
    except Exception as e:
        return {"ok": False, "error": str(e), "input": input_path}


def _save_pil(im, path: str, fmt: str, quality: int, lossless: bool) -> Dict[str, Any]:
    try:
        save_kwargs = {}
        fmt_upper = fmt.upper()
        if fmt_upper in ("JPEG", "JPG"):
            fmt_upper = "JPEG"
            if im.mode in ("RGBA", "P", "LA"):
                bg = Image.new("RGB", im.size, (255, 255, 255))
                bg.paste(im, mask=im.split()[-1] if im.mode in ("RGBA", "LA") else None)
                im = bg
            save_kwargs["quality"] = quality
        elif fmt_upper == "WEBP":
            save_kwargs["quality"] = quality
            save_kwargs["lossless"] = lossless
        _ensure_dir(path)
        im.save(path, format=fmt_upper, **save_kwargs)
        return {"ok": True, "output": path, "size_out": os.path.getsize(path)}
    except Exception as e:
        return {"ok": False, "error": str(e)}


def process_file(
    input_path: str,
    output_dir: str,
    fmt: str = "webp",
    quality: int = 80,
    lossless: bool = False,
    visible_only: bool = True,
    export_layers: bool = True,
) -> Dict[str, Any]:
    """Entry point called from Kotlin."""
    ext = Path(input_path).suffix.lower()
    if ext in SUPPORTED_PSD:
        return convert_psd(
            input_path, output_dir, fmt, quality, lossless,
            visible_only=visible_only, export_layers=export_layers
        )
    elif ext in SUPPORTED_RASTER or True:  # try anything Pillow can open
        out_name = Path(input_path).stem + f".{fmt}"
        output_path = str(Path(output_dir) / out_name)
        return convert_raster(input_path, output_path, fmt, quality, lossless)
    else:
        return {"ok": False, "error": f"Unsupported extension: {ext}"}
