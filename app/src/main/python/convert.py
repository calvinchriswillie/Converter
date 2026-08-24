"""
Robust raster conversion with repair for partial/corrupt images.
Returns dict with ok as "1" or "0" for reliable Kotlin/Chaquopy reading.
"""
from __future__ import annotations
import os
import traceback
from pathlib import Path

try:
    from PIL import Image, ImageFile
    ImageFile.LOAD_TRUNCATED_IMAGES = True
except ImportError:
    Image = None


def _crop_visible(im):
    try:
        if im.mode in ("RGBA", "LA"):
            bbox = im.split()[-1].getbbox()
            if bbox:
                return im.crop(bbox)
        bbox = im.getbbox()
        if bbox and bbox != (0, 0, im.width, im.height):
            return im.crop(bbox)
    except Exception:
        pass
    return im


def _try_open(path):
    if Image is None:
        return None, "Pillow not available"
    try:
        im = Image.open(path)
        im.load()
        return im, "ok"
    except Exception as e1:
        try:
            return Image.open(path).copy(), "partial:" + str(e1)
        except Exception as e2:
            return None, "unreadable:" + str(e1) + " | " + str(e2)


def process_file(
    input_path,
    output_path,
    fmt="webp",
    quality=80,
    lossless=False,
    visible_only=True,
    export_layers=True,
    crop_visible=True,
):
    try:
        if Image is None:
            return {"ok": "0", "error": "Pillow not available", "repaired": "0"}
        if Path(input_path).suffix.lower() in {".psd", ".psb"}:
            return {"ok": "0", "error": "PSD not supported yet", "repaired": "0"}

        im, note = _try_open(input_path)
        if im is None:
            return {"ok": "0", "error": note, "repaired": "0"}

        repaired = "0" if note == "ok" else "1"
        if crop_visible:
            im = _crop_visible(im)

        fmt_l = (fmt or "webp").lower()
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
        fmt_upper = fmt_l.upper()
        if fmt_upper in ("JPEG", "JPG"):
            fmt_upper = "JPEG"
            save_kwargs = {"quality": int(quality), "optimize": True}
        elif fmt_upper == "WEBP":
            save_kwargs = {
                "quality": int(quality),
                "lossless": bool(lossless),
                "method": 4,
            }
        elif fmt_upper == "PNG":
            save_kwargs = {"optimize": True}

        parent = os.path.dirname(output_path)
        if parent:
            os.makedirs(parent, exist_ok=True)

        im.save(output_path, format=fmt_upper, **save_kwargs)
        try:
            im.close()
        except Exception:
            pass

        if not os.path.isfile(output_path) or os.path.getsize(output_path) == 0:
            return {"ok": "0", "error": "save produced empty file", "repaired": repaired}

        return {
            "ok": "1",
            "error": "",
            "repaired": repaired,
            "note": note,
            "output": output_path,
            "size_out": str(os.path.getsize(output_path)),
        }
    except Exception as e:
        return {
            "ok": "0",
            "error": str(e) + "\n" + traceback.format_exc(),
            "repaired": "0",
        }
