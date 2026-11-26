# Generates the TV banner (320x180) and app icon (192x192) of Best Seat Helper.
# Usage: python3 icon/gen.py   (writes into res/)
# Style matches the Best Seat launcher: graphite gradient, amber glow, amber armchair.
import os
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
S = 8  # supersampling
AMBER = (255, 176, 64)
AMBER_EDGE = (196, 120, 30)
FONT = "/System/Library/Fonts/Avenir Next.ttc"


def background(w, h, cx, cy, r):
    img = Image.new("RGBA", (w, h))
    d = ImageDraw.Draw(img)
    for y in range(h):
        t = y / h
        d.line([(0, y), (w, y)], fill=(int(30 - 20 * t), int(32 - 21 * t), int(40 - 25 * t), 255))
    g = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    ImageDraw.Draw(g).ellipse([cx - r, cy - r * 0.75, cx + r, cy + r * 0.75], fill=AMBER + (70,))
    return Image.alpha_composite(img, g.filter(ImageFilter.GaussianBlur(r * 0.45)))


def armchair(d, x, y, s, color, width, fill=None):
    """Armchair: back, armrests, seat, feet. (x, y) = top left, s = width."""
    w = int(width)
    h = s * 0.82
    r = s * 0.12
    d.rounded_rectangle([x + s * 0.16, y, x + s * 0.84, y + h * 0.62], r, outline=color, width=w, fill=fill)
    d.rounded_rectangle([x, y + h * 0.30, x + s * 0.2, y + h * 0.88], r * 0.8, outline=color, width=w, fill=fill)
    d.rounded_rectangle([x + s * 0.8, y + h * 0.30, x + s, y + h * 0.88], r * 0.8, outline=color, width=w, fill=fill)
    d.rounded_rectangle([x + s * 0.2, y + h * 0.58, x + s * 0.8, y + h * 0.88], r * 0.6, outline=color, width=w, fill=fill)
    d.line([(x + s * 0.12, y + h * 0.88), (x + s * 0.12, y + h)], fill=color, width=w)
    d.line([(x + s * 0.88, y + h * 0.88), (x + s * 0.88, y + h)], fill=color, width=w)


def screen(img, x, y, w):
    """TV screen with the armchair on the left and three switches on the right."""
    h = w * 9 / 16
    u = w / 120  # unit: the original drawing was 120 wide
    ov = Image.new("RGBA", img.size, (0, 0, 0, 0))
    od = ImageDraw.Draw(ov)
    od.rounded_rectangle([x, y, x + w, y + h], 11 * u, fill=(255, 255, 255, 20),
                         outline=(255, 255, 255, 215), width=int(4 * u))
    od.rounded_rectangle([x + 44 * u, y + h + 6.5 * u, x + 76 * u, y + h + 11.5 * u], 2.5 * u,
                         fill=(255, 255, 255, 215))
    img = Image.alpha_composite(img, ov)
    d = ImageDraw.Draw(img)
    s = 34 * u
    ax, ay = x + 12 * u, y + (h - s * 0.82) / 2
    armchair(d, ax, ay, s, AMBER, 2.2 * u, fill=AMBER)
    armchair(d, ax, ay, s, AMBER_EDGE, 1.2 * u)
    for i, on in enumerate((True, True, False)):
        cy = y + h / 2 + (i - 1) * 16 * u
        d.rounded_rectangle([x + 60 * u, cy - 2 * u, x + (75 if i < 2 else 71) * u, cy + 2 * u], 2 * u,
                            fill=(200, 203, 210, 140))
        d.rounded_rectangle([x + 80 * u, cy - 4.5 * u, x + 100 * u, cy + 4.5 * u], 4.5 * u,
                            fill=AMBER if on else (96, 98, 106))
        kx = x + (94.5 if on else 85.5) * u
        d.ellipse([kx - 5.6 * u, cy - 5.6 * u, kx + 5.6 * u, cy + 5.6 * u],
                  fill=(255, 255, 255) if on else (170, 170, 178))
    return img


def banner():
    w, h = 320 * S, 180 * S
    img = background(w, h, w / 2, 69 * S, 150 * S)
    img = screen(img, 100 * S, 35 * S, 120 * S)
    d = ImageDraw.Draw(img)
    f = ImageFont.truetype(FONT, 21 * S, index=2)
    d.text((w / 2, 151 * S), "Best Seat Helper", font=f, fill=(245, 245, 250), anchor="mm")
    return img.resize((320, 180), Image.LANCZOS).convert("RGB")


def icon():
    n = 192 * S
    img = background(n, n, n / 2, n / 2, n * 0.55)
    img = screen(img, n * 0.14, n * 0.27, n * 0.72)
    mask = Image.new("L", (n, n), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, n - 1, n - 1], n * 0.22, fill=255)
    img.putalpha(mask)
    return img.resize((192, 192), Image.LANCZOS)


banner().save(os.path.join(ROOT, "res/drawable-xhdpi/banner.png"))
icon().save(os.path.join(ROOT, "res/mipmap-xxxhdpi/ic_launcher.png"))
