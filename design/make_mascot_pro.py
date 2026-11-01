#!/usr/bin/env python3
"""Hoot Pro — DuckDuckGo-grade mascot for Private X.

Layered flat-vector owl bust: gradient head with ear tufts, expressive
browed eyes, scalloped chest feathers, and a shield-emblem chest badge
(the signature accessory). Renders: character mark, badge logo, app-icon
preview, and a monochrome silhouette for themed icons.
"""
import os
from PIL import Image, ImageDraw, ImageFilter

DESIGN = os.path.expanduser("~/workspace/private-x/design")
SS = 4
MASTER = 1080

INK = (5, 16, 30)
PALE = (236, 253, 245)


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def cubic(p0, p1, p2, p3, n=40):
    pts = []
    for i in range(n + 1):
        t = i / n
        mt = 1 - t
        pts.append((
            mt**3 * p0[0] + 3 * mt**2 * t * p1[0] + 3 * mt * t**2 * p2[0] + t**3 * p3[0],
            mt**3 * p0[1] + 3 * mt**2 * t * p1[1] + 3 * mt * t**2 * p2[1] + t**3 * p3[1],
        ))
    return pts


def diag_gradient(size, c0, c1):
    g = Image.new("RGB", (size, size))
    px = g.load()
    for y in range(size):
        for x in range(0, size, 8):
            col = lerp(c0, c1, (x + y) / (2 * (size - 1)))
            for dx in range(8):
                if x + dx < size:
                    px[x + dx, y] = col
    return g


def vert_gradient(size, c0, c1):
    g = Image.new("RGB", (size, size))
    px = g.load()
    for y in range(size):
        col = lerp(c0, c1, y / (size - 1))
        for x in range(size):
            px[x, y] = col
    return g


class C:
    def __init__(self, big):
        self.big = big
        self.s = big / MASTER
        self.img = Image.new("RGBA", (big, big), (0, 0, 0, 0))
        self.d = ImageDraw.Draw(self.img)

    def P(self, pts):
        return [(x * self.s, y * self.s) for (x, y) in pts]

    def poly(self, pts, fill):
        self.d.polygon(self.P(pts), fill=fill)

    def ell(self, cx, cy, rx, ry, fill):
        s = self.s
        self.d.ellipse([(cx - rx) * s, (cy - ry) * s, (cx + rx) * s, (cy + ry) * s], fill=fill)

    def paste_masked(self, src_rgba, mask_pts):
        mask = Image.new("L", (self.big, self.big), 0)
        ImageDraw.Draw(mask).polygon(self.P(mask_pts), fill=255)
        layer = Image.new("RGBA", (self.big, self.big), (0, 0, 0, 0))
        layer.paste(src_rgba, (0, 0), mask)
        self.img = Image.alpha_composite(self.img, layer)
        self.d = ImageDraw.Draw(self.img)


def body_points():
    pts = []
    pts += cubic((292, 742), (360, 676), (470, 660), (540, 668))
    pts += cubic((540, 668), (610, 660), (720, 676), (788, 742))
    pts += cubic((788, 742), (806, 850), (812, 960), (816, 1080))
    pts += [(264, 1080)]
    pts += cubic((268, 960), (274, 850), (292, 742), (292, 742))
    return pts


def draw_hoot(big, mono=False):
    c = C(big)
    s = c.s
    white = (255, 255, 255, 255)

    def col(rgb, a=255):
        return white if mono else rgb + (a,)

    # ---- ear tufts (behind head)
    tuft_l = cubic((352, 262), (300, 150), (268, 110), (262, 92)) + \
        cubic((262, 92), (330, 120), (420, 168), (470, 232))
    tuft_r = [(1080 - x, y) for (x, y) in reversed(tuft_l)]
    if mono:
        c.poly(tuft_l, white)
        c.poly(tuft_r, white)
    else:
        grad = diag_gradient(big, (45, 212, 191), (8, 145, 178)).convert("RGBA")
        c.paste_masked(grad, tuft_l)
        c.paste_masked(grad, tuft_r)
        c.d = ImageDraw.Draw(c.img)
        inner_l = cubic((356, 240), (322, 168), (306, 140), (302, 128)) + \
            cubic((302, 128), (352, 150), (404, 182), (428, 218))
        inner_r = [(1080 - x, y) for (x, y) in reversed(inner_l)]
        c.poly(inner_l, (165, 243, 252, 160))
        c.poly(inner_r, (165, 243, 252, 160))

    # ---- body
    if mono:
        c.poly(body_points(), white)
    else:
        grad = vert_gradient(big, (13, 148, 136), (10, 110, 104)).convert("RGBA")
        c.paste_masked(grad, body_points())
        c.d = ImageDraw.Draw(c.img)
        # wings (darker side shapes)
        wing_l = cubic((300, 756), (352, 830), (366, 950), (352, 1080)) + \
            cubic((352, 1080), (300, 1000), (272, 880), (276, 800))
        wing_r = [(1080 - x, y) for (x, y) in reversed(wing_l)]
        c.poly(wing_l, (8, 84, 78, 190))
        c.poly(wing_r, (8, 84, 78, 190))
        # chest: lighter belly panel + scallop arcs, clipped to the body
        body_mask = Image.new("L", (big, big), 0)
        ImageDraw.Draw(body_mask).polygon(c.P(body_points()), fill=255)
        chest = Image.new("RGBA", (big, big), (0, 0, 0, 0))
        cd = ImageDraw.Draw(chest)
        cd.ellipse([(540 - 168) * s, (700) * s, (540 + 168) * s, (1120) * s],
                   fill=(94, 234, 212, 36))
        for row, yy in enumerate((838, 916, 994, 1072)):
            offset = 46 if row % 2 else 0
            for xx in range(392 + offset, 700, 92):
                cd.arc([(xx - 40) * s, (yy - 36) * s, (xx + 40) * s, (yy + 36) * s],
                       start=25, end=155, fill=(153, 246, 228, 120), width=int(8 * s))
        chest.putalpha(Image.composite(chest.split()[3], Image.new("L", (big, big), 0), body_mask))
        c.img = Image.alpha_composite(c.img, chest)
        c.d = ImageDraw.Draw(c.img)

    # ---- head
    if mono:
        c.ell(540, 428, 298, 292, white)
    else:
        grad = diag_gradient(big, (45, 212, 191), (8, 145, 178)).convert("RGBA")
        mask = Image.new("L", (big, big), 0)
        ImageDraw.Draw(mask).ellipse(
            [(540 - 298) * s, (428 - 292) * s, (540 + 298) * s, (428 + 292) * s], fill=255)
        layer = Image.new("RGBA", (big, big), (0, 0, 0, 0))
        layer.paste(grad, (0, 0), mask)
        # soft top-left highlight + gentle bottom shade, inside the head
        hl = Image.new("RGBA", (big, big), (0, 0, 0, 0))
        ImageDraw.Draw(hl).ellipse([(300) * s, (120) * s, (620) * s, (380) * s],
                                   fill=(255, 255, 255, 42))
        hl = hl.filter(ImageFilter.GaussianBlur(big * 0.05))
        hl.putalpha(Image.composite(hl.split()[3], Image.new("L", (big, big), 0), mask))
        layer = Image.alpha_composite(layer, hl)
        c.img = Image.alpha_composite(c.img, layer)
        c.d = ImageDraw.Draw(c.img)

    if mono:
        # knock out the eyes + beak
        for ex in (450, 630):
            c.ell(ex, 478, 62, 62, (0, 0, 0, 0))
            hole = Image.new("L", (big, big), 0)
            ImageDraw.Draw(hole).ellipse(
                [(ex - 62) * s, (478 - 62) * s, (ex + 62) * s, (478 + 62) * s], fill=255)
            alpha = c.img.split()[3]
            c.img.putalpha(Image.composite(Image.new("L", (big, big), 0), alpha, hole))
            c.d = ImageDraw.Draw(c.img)
        return c.img

    # ---- face (pale heart)
    c.ell(450, 478, 152, 158, PALE + (255,))
    c.ell(630, 478, 152, 158, PALE + (255,))
    c.ell(540, 566, 138, 128, PALE + (255,))

    # ---- brows: curved ridges angled down toward the centre (watchful)
    for ex, flip in ((450, 1), (630, -1)):
        brow = cubic((ex - 92 * flip, 372), (ex - 40 * flip, 322),
                     (ex + 44 * flip, 318), (ex + 94 * flip, 344), n=24)
        c.d.line(c.P(brow), fill=(10, 110, 104, 255), width=int(21 * s))

    # ---- eyes
    for ex in (450, 630):
        c.ell(ex, 478, 88, 88, (255, 255, 255, 255))
        iris = diag_gradient(big, (34, 211, 238), (13, 148, 136)).convert("RGBA")
        imask = Image.new("L", (big, big), 0)
        ImageDraw.Draw(imask).ellipse(
            [(ex - 55) * s, (478 - 55) * s, (ex + 55) * s, (478 + 55) * s], fill=255)
        layer = Image.new("RGBA", (big, big), (0, 0, 0, 0))
        layer.paste(iris, (0, 0), imask)
        c.img = Image.alpha_composite(c.img, layer)
        c.d = ImageDraw.Draw(c.img)
        c.ell(ex, 478, 26, 26, INK + (255,))
        c.ell(ex + 17, 459, 11, 11, (255, 255, 255, 255))
        c.ell(ex - 13, 493, 6, 6, (255, 255, 255, 230))

    # ---- beak (layered)
    c.poly([(540, 622), (494, 566), (586, 566)], (245, 158, 11, 255))
    c.poly([(540, 652), (510, 610), (570, 610)], (217, 119, 6, 255))

    # ---- chest shield emblem (the signature accessory)
    sh = []
    cx, cy, k = 540, 856, 0.155
    def SP(x, y):
        return (cx + (x - 540) * k, cy + (y - 472) * k)
    sh += cubic(SP(292, 178), SP(420, 158), SP(660, 158), SP(788, 178))
    sh += cubic(SP(788, 178), SP(806, 400), SP(706, 664), SP(540, 862))
    sh += cubic(SP(540, 862), SP(374, 664), SP(274, 400), SP(292, 178))
    c.poly(sh, (34, 211, 238, 255))
    alm = []
    alm += cubic(SP(330, 472), SP(430, 300), SP(650, 300), SP(750, 472))
    alm += cubic(SP(750, 472), SP(650, 644), SP(430, 644), SP(330, 472))
    c.poly(alm, INK + (255,))
    c.ell(cx, cy, 15, 15, (165, 243, 252, 255))
    return c.img


def shield_badge(size_px, mark_img):
    big = size_px * SS
    badge = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    d = ImageDraw.Draw(badge)
    grad = vert_gradient(big, (45, 212, 191), (13, 148, 136)).convert("RGBA")
    mask = Image.new("L", (big, big), 0)
    ImageDraw.Draw(mask).ellipse([10, 10, big - 10, big - 10], fill=255)
    badge.paste(grad, (0, 0), mask)
    d = ImageDraw.Draw(badge)
    d.ellipse([14, 14, big - 14, big - 14], outline=(204, 251, 241, 255), width=int(10 * SS))
    m = mark_img.resize((int(big * 0.86), int(big * 0.86)), Image.LANCZOS)
    inner = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    inner.alpha_composite(m, ((big - m.width) // 2, int(big * 0.10)))
    inner.putalpha(Image.composite(inner.split()[3], Image.new("L", (big, big), 0), mask))
    badge = Image.alpha_composite(badge, inner)
    return badge.resize((size_px, size_px), Image.LANCZOS)


def navy_bg(size_px):
    big = size_px * SS
    img = vert_gradient(big, (9, 28, 48), (4, 7, 15)).convert("RGBA")
    glow = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow)
    r = big * 0.36
    ctr = big / 2
    gd.ellipse([ctr - r, ctr - r * 1.05, ctr + r, ctr + r * 1.05], fill=(45, 212, 191, 55))
    glow = glow.filter(ImageFilter.GaussianBlur(big * 0.09))
    img = Image.alpha_composite(img, glow)
    return img.resize((size_px, size_px), Image.LANCZOS)


if __name__ == "__main__":
    mark_big = draw_hoot(864 * SS)
    mark_big.resize((864, 864), Image.LANCZOS).save(
        os.path.join(DESIGN, "mascot-pro-mark.png"))
    mono_big = draw_hoot(864 * SS, mono=True)
    mono_big.resize((864, 864), Image.LANCZOS).save(
        os.path.join(DESIGN, "mascot-pro-mono.png"))
    badge = shield_badge(1024, draw_hoot(1024 * SS))
    badge.save(os.path.join(DESIGN, "mascot-pro-badge.png"))
    prev = navy_bg(1024)
    b = badge.resize((940, 940), Image.LANCZOS)
    prev.alpha_composite(b, ((1024 - 940) // 2, (1024 - 940) // 2))
    prev.save(os.path.join(DESIGN, "mascot-pro-preview.png"))
    print("done")
