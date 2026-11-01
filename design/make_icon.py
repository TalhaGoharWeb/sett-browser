#!/usr/bin/env python3
"""Private X icon generator — gradient shield-eye mark.

Draws one master mark with PIL at 4x supersampling, then emits every
Android launcher asset: adaptive background/foreground layers per
density, legacy mipmaps, the Android 13 monochrome layer, and previews.
"""
import math
import os
from PIL import Image, ImageDraw, ImageFilter

RES = os.path.expanduser("~/workspace/private-x/android/app/src/main/res")
DESIGN = os.path.expanduser("~/workspace/private-x/design")
SS = 4            # supersampling factor
MASTER = 1080     # master canvas (maps to the 108dp adaptive canvas)

NAVY_DEEP = (4, 7, 15)
NAVY_TOP = (9, 28, 48)
TEAL = (45, 212, 191)
CYAN = (14, 165, 198)
EYE_NAVY = (5, 16, 30)
PUPIL = (6, 24, 38)


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def cubic(p0, p1, p2, p3, n=48):
    pts = []
    for i in range(n + 1):
        t = i / n
        mt = 1 - t
        x = mt**3 * p0[0] + 3 * mt**2 * t * p1[0] + 3 * mt * t**2 * p2[0] + t**3 * p3[0]
        y = mt**3 * p0[1] + 3 * mt**2 * t * p1[1] + 3 * mt * t**2 * p2[1] + t**3 * p3[1]
        pts.append((x, y))
    return pts


def quad(p0, p1, p2, n=40):
    pts = []
    for i in range(n + 1):
        t = i / n
        mt = 1 - t
        x = mt**2 * p0[0] + 2 * mt * t * p1[0] + t**2 * p2[0]
        y = mt**2 * p0[1] + 2 * mt * t * p1[1] + t**2 * p2[1]
        pts.append((x, y))
    return pts


def shield_points(s=1.0, cx=540, cy=540):
    """Heater-shield outline centred on (cx, cy); s scales the 1080 master."""
    def P(x, y):
        return (cx + (x - 540) * s, cy + (y - 540) * s)
    pts = []
    pts += cubic(P(292, 178), P(420, 158), P(660, 158), P(788, 178))      # top edge
    pts += cubic(P(788, 178), P(806, 400), P(706, 664), P(540, 862))      # right flank
    pts += cubic(P(540, 862), P(374, 664), P(274, 400), P(292, 178))      # left flank
    return pts


def almond_points(s=1.0, cx=540, cy=472):
    def P(x, y):
        return (cx + (x - 540) * s, cy + (y - 472) * s)
    pts = quad(P(330, 472), P(540, 268), P(750, 472))
    pts += quad(P(750, 472), P(540, 676), P(330, 472))
    return pts


def gradient_shield_mask(size_px):
    big = size_px * SS
    scale = big / MASTER
    mask = Image.new("L", (big, big), 0)
    d = ImageDraw.Draw(mask)
    d.polygon([(x * scale, y * scale) for (x, y) in shield_points()], fill=255)
    return mask


def diagonal_gradient(size, c0, c1):
    g = Image.new("RGB", (size, size))
    px = g.load()
    for y in range(size):
        for x in range(0, size, 8):
            t = (x + y) / (2 * (size - 1))
            col = lerp(c0, c1, t)
            for dx in range(8):
                if x + dx < size:
                    px[x + dx, y] = col
    return g


def make_mark(size_px, mode="color"):
    big = size_px * SS
    scale = big / MASTER
    img = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    shield = [(x * scale, y * scale) for (x, y) in shield_points()]
    almond = [(x * scale, y * scale) for (x, y) in almond_points()]
    eye_c = (540 * scale, 472 * scale)
    iris_r, pupil_r = 97 * scale, 45 * scale

    if mode == "mono":
        d.polygon(shield, fill=(255, 255, 255, 255))
        # knock the almond out of the alpha channel
        hole = Image.new("L", (big, big), 0)
        ImageDraw.Draw(hole).polygon(almond, fill=255)
        alpha = img.split()[3]
        alpha = Image.composite(Image.new("L", (big, big), 0), alpha, hole)
        img.putalpha(alpha)
        d = ImageDraw.Draw(img)
        d.ellipse([eye_c[0] - pupil_r, eye_c[1] - pupil_r,
                   eye_c[0] + pupil_r, eye_c[1] + pupil_r], fill=(255, 255, 255, 255))
        return img.resize((size_px, size_px), Image.LANCZOS)

    # gradient shield body, clipped to the shield shape
    grad = diagonal_gradient(big, TEAL, CYAN).convert("RGBA")
    mask = gradient_shield_mask(size_px)
    img.paste(grad, (0, 0), mask)
    d = ImageDraw.Draw(img)
    d = ImageDraw.Draw(img)
    # eye: navy almond, cyan iris, navy pupil, white glint
    d.polygon(almond, fill=EYE_NAVY + (255,))
    d.ellipse([eye_c[0] - iris_r, eye_c[1] - iris_r, eye_c[0] + iris_r, eye_c[1] + iris_r],
              fill=(103, 232, 249, 255))
    d.ellipse([eye_c[0] - pupil_r, eye_c[1] - pupil_r, eye_c[0] + pupil_r, eye_c[1] + pupil_r],
              fill=PUPIL + (255,))
    gl = 15 * scale
    gx, gy = eye_c[0] + 26 * scale, eye_c[1] - 30 * scale
    d.ellipse([gx - gl, gy - gl, gx + gl, gy + gl], fill=(255, 255, 255, 235))
    return img.resize((size_px, size_px), Image.LANCZOS)


def make_background(size_px):
    big = size_px * SS
    img = Image.new("RGB", (big, big))
    px = img.load()
    for y in range(big):
        t = y / (big - 1)
        col = lerp(NAVY_TOP, NAVY_DEEP, t)
        for x in range(0, big, 8):
            for dx in range(8):
                if x + dx < big:
                    px[x + dx, y] = col
    img = img.convert("RGBA")
    # teal glow behind the shield
    glow = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow)
    r = big * 0.34
    c = big / 2
    gd.ellipse([c - r, c - r * 1.08, c + r, c + r * 1.08], fill=(45, 212, 191, 60))
    glow = glow.filter(ImageFilter.GaussianBlur(big * 0.09))
    img = Image.alpha_composite(img, glow)
    return img.resize((size_px, size_px), Image.LANCZOS)


def paste_center(base, mark, frac):
    """Paste mark scaled to frac of base size, centred."""
    m = mark.resize((int(base.width * frac), int(base.height * frac)), Image.LANCZOS)
    base.alpha_composite(m, ((base.width - m.width) // 2, (base.height - m.height) // 2))
    return base


def save(img, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)
    print("wrote", path, img.size)


DENS = {  # density bucket -> (legacy 48dp px, adaptive 108dp px)
    "mdpi": (48, 108), "hdpi": (72, 162), "xhdpi": (96, 216),
    "xxhdpi": (144, 324), "xxxhdpi": (192, 432),
}

mark_master = make_mark(864)
mono_master = make_mark(864, mode="mono")

for bucket, (legacy_px, adaptive_px) in DENS.items():
    folder = os.path.join(RES, f"mipmap-{bucket}")
    bg = make_background(adaptive_px)
    save(bg, os.path.join(folder, "ic_launcher_background.png"))
    fg = Image.new("RGBA", (adaptive_px, adaptive_px), (0, 0, 0, 0))
    paste_center(fg, mark_master, 0.74)
    save(fg, os.path.join(folder, "ic_launcher_foreground.png"))
    legacy_bg = make_background(legacy_px)
    paste_center(legacy_bg, mark_master, 0.94)
    save(legacy_bg, os.path.join(folder, "ic_launcher.png"))

mono = Image.new("RGBA", (432, 432), (0, 0, 0, 0))
paste_center(mono, mono_master, 0.74)
save(mono, os.path.join(RES, "drawable", "ic_launcher_monochrome.png"))

# previews for the design folder
prev_bg = make_background(512)
paste_center(prev_bg, mark_master, 0.94)
save(prev_bg, os.path.join(DESIGN, "icon-preview-512.png"))
save(mark_master, os.path.join(DESIGN, "mark-864.png"))
print("done")
