"""Generate Android launcher / notification icons from the whale-girl artwork.

Input:  art/improved-1.png  (984x984, transparent background)
Output: mipmap-*/ic_launcher_*.png      legacy launcher icons
        drawable-*/ic_launcher_fg.png   adaptive-icon foreground layer
        drawable-*/ic_notification.png  flat white silhouette for status bar

The artwork is CC BY-NC-SA 4.0; see THIRD_PARTY_LICENSES.md.
"""

from __future__ import annotations

import sys
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
ART = ROOT / "art" / "improved-1.png"
RES = ROOT / "app" / "src" / "main" / "res"

# Density bucket -> launcher icon side in px (48dp baseline).
LAUNCHER_SIZES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}

# Adaptive icons are 108dp; the visible safe zone is the centre 72dp (~66%).
ADAPTIVE_SIZES = {
    "mdpi": 108,
    "hdpi": 162,
    "xhdpi": 216,
    "xxhdpi": 324,
    "xxxhdpi": 432,
}

NOTIF_SIZES = {
    "mdpi": 24,
    "hdpi": 36,
    "xhdpi": 48,
    "xxhdpi": 72,
    "xxxhdpi": 96,
}


def trim_and_pad(img: Image.Image) -> Image.Image:
    """Trim transparent margins, then pad back to a square.

    The source art has uneven transparent space; without trimming the subject
    sits off-centre, and an off-centre launcher icon looks like a mistake at
    48dp.
    """
    bbox = img.getbbox()
    if bbox:
        img = img.crop(bbox)
    side = max(img.width, img.height)
    canvas = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    canvas.paste(img, ((side - img.width) // 2, (side - img.height) // 2))
    return canvas


def make_launcher(src: Image.Image, size: int) -> Image.Image:
    """Legacy square icon: subject fills most of the frame, no extra margin."""
    return src.resize((size, size), Image.LANCZOS)


def make_adaptive_fg(src: Image.Image, size: int) -> Image.Image:
    """Adaptive foreground: subject occupies the central safe zone only.

    Android masks the outer ~25% of an adaptive icon, so a full-bleed subject
    gets its ears and tail clipped. Scaling to 62% keeps the whole character
    inside the circle/squircle masks every launcher uses.
    """
    inner = int(size * 0.62)
    scaled = src.resize((inner, inner), Image.LANCZOS)
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    canvas.paste(scaled, ((size - inner) // 2, (size - inner) // 2))
    return canvas


def make_notification(src: Image.Image, size: int) -> Image.Image:
    """Status-bar icon: flat white silhouette on transparent.

    Android tints small icons itself and only reads the alpha channel, so this
    must be a solid white shape — any colour or shading collapses into a blob.
    """
    alpha = src.resize((size, size), Image.LANCZOS).getchannel("A")
    white = Image.new("RGBA", (size, size), (255, 255, 255, 0))
    white.putalpha(alpha)
    return white


def main() -> int:
    if not ART.exists():
        print(f"missing artwork: {ART}", file=sys.stderr)
        return 1

    src = trim_and_pad(Image.open(ART).convert("RGBA"))
    print(f"source after trim/pad: {src.width}x{src.height}")

    wrote = 0
    for bucket, size in LAUNCHER_SIZES.items():
        out_dir = RES / f"mipmap-{bucket}"
        out_dir.mkdir(parents=True, exist_ok=True)
        for name in ("ic_launcher.png", "ic_launcher_round.png"):
            make_launcher(src, size).save(out_dir / name)
            wrote += 1

    for bucket, size in ADAPTIVE_SIZES.items():
        out_dir = RES / f"drawable-{bucket}"
        out_dir.mkdir(parents=True, exist_ok=True)
        make_adaptive_fg(src, size).save(out_dir / "ic_launcher_fg.png")
        wrote += 1

    for bucket, size in NOTIF_SIZES.items():
        out_dir = RES / f"drawable-{bucket}"
        out_dir.mkdir(parents=True, exist_ok=True)
        make_notification(src, size).save(out_dir / "ic_notification.png")
        wrote += 1

    print(f"wrote {wrote} images")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
