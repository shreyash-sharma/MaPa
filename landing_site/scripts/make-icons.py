"""Build the favicon set from public/mapa-emblem-hd.png.

Writes src/app/favicon.ico (16, 32, 48), src/app/icon.png (512, transparent)
and src/app/apple-icon.png (180, on the site's cream surface; iOS fills
transparency with black). Run: python scripts/make-icons.py
"""

from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
APP = ROOT / "src" / "app"
SURFACE = (252, 249, 244, 255)


def square(img: Image.Image, size: int, pad: float, bg=(0, 0, 0, 0)) -> Image.Image:
    art = img.crop(img.getbbox())
    inner = round(size * (1 - 2 * pad))
    scale = inner / max(art.size)
    art = art.resize((max(1, round(art.width * scale)), max(1, round(art.height * scale))), Image.LANCZOS)
    canvas = Image.new("RGBA", (size, size), bg)
    canvas.alpha_composite(art, ((size - art.width) // 2, (size - art.height) // 2))
    return canvas


def main() -> None:
    src = Image.open(ROOT / "public" / "mapa-emblem-hd.png").convert("RGBA")
    big = square(src, 256, pad=0.02)
    big.save(APP / "favicon.ico", sizes=[(16, 16), (32, 32), (48, 48)])
    square(src, 512, pad=0.04).save(APP / "icon.png")
    square(src, 180, pad=0.14, bg=SURFACE).save(APP / "apple-icon.png")
    print("wrote favicon.ico, icon.png, apple-icon.png")


if __name__ == "__main__":
    main()
