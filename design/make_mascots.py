#!/usr/bin/env python3
"""Private X mascot concepts — four flat geometric mascots on the brand
navy background, drawn with PIL at 4x supersampling.

A: Hoot the owl (watchful)      B: Vex the fox (elusive)
C: Kamo the chameleon (blends)  D: Sentinel the meerkat (lookout)
"""
import math
import os
from PIL import Image, ImageDraw, ImageFilter

DESIGN = os.path.expanduser("~/workspace/private-x/design")
SS = 4
MASTER = 1080

NAVY_DEEP = (4, 7, 15)
NAVY_TOP = (9, 28, 48)
TEAL = (45, 212, 191)
CYAN = (34, 211, 238)
INK = (5, 16, 30)


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


def mirror_x(pts, cx=540):
    return [(2 * cx - x, y) for (x, y) in reversed(pts)]


class Canvas:
    def __init__(self, size_px):
        self.big = size_px * SS
        self.s = self.big / MASTER
        self.img = Image.new("RGBA", (self.big, self.big), (0, 0, 0, 0))
        self.d = ImageDraw.Draw(self.img)

    def P(self, pts):
        return [(x * self.s, y * self.s) for (x, y) in pts]

    def poly(self, pts, fill):
        self.d.polygon(self.P(pts), fill=fill)

    def ellipse(self, cx, cy, rx, ry, fill):
        s = self.s
        self.d.ellipse([(cx - rx) * s, (cy - ry) * s, (cx + rx) * s, (cy + ry) * s], fill=fill)

    def finish(self, size_px):
        return self.img.resize((size_px, size_px), Image.LANCZOS)


def diagonal_gradient(size, c0, c1):
    g = Image.new("RGB", (size, size))
    px = g.load()
    for y in range(size):
        for x in range(0, size, 8):
            col = lerp(c0, c1, (x + y) / (2 * (size - 1)))
            for dx in range(8):
                if x + dx < size:
                    px[x + dx, y] = col
    return g


def vertical_gradient(size, c0, c1):
    g = Image.new("RGB", (size, size))
    px = g.load()
    for y in range(size):
        col = lerp(c0, c1, y / (size - 1))
        for x in range(size):
            px[x, y] = col
    return g


def clip_to_mask(img_rgba, mask_pts, canvas):
    mask = Image.new("L", canvas.img.size, 0)
    ImageDraw.Draw(mask).polygon(canvas.P(mask_pts), fill=255)
    out = Image.new("RGBA", canvas.img.size, (0, 0, 0, 0))
    out.paste(img_rgba, (0, 0), mask)
    return Image.alpha_composite(canvas.img, out), mask


# --------------------------------------------------------------------- A owl
def mascot_owl(size_px):
    c = Canvas(size_px)
    big = c.big
    # head silhouette: circle + ear tufts
    head_mask_pts = cubic((250, 300), (300, 130), (430, 190), (470, 250)) + \
        cubic((470, 250), (520, 190), (560, 190), (610, 250)) + \
        cubic((610, 250), (650, 190), (780, 130), (830, 300))
    grad = diagonal_gradient(big, (13, 148, 136), (8, 145, 178)).convert("RGBA")
    c.img, _ = clip_to_mask(grad, head_mask_pts, c)
    c.d = ImageDraw.Draw(c.img)
    c.ellipse(540, 570, 335, 320, (13, 148, 136, 255))  # round out the head
    c.img = Image.alpha_composite(
        diagonal_gradient(big, (13, 148, 136), (8, 145, 178)).convert("RGBA"),
        c.img) if False else c.img
    # re-draw silhouette cleanly: gradient head disc + tufts
    c2 = Canvas(size_px)
    grad = diagonal_gradient(big, (20, 184, 166), (2, 132, 199)).convert("RGBA")
    disc = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    dd = ImageDraw.Draw(disc)
    s = c2.s
    dd.ellipse([(540 - 335) * s, (570 - 320) * s, (540 + 335) * s, (570 + 320) * s], fill=(255, 255, 255, 255))
    dd.polygon(c2.P([(268, 330), (318, 128), (478, 268)]), fill=(255, 255, 255, 255))
    dd.polygon(c2.P([(812, 330), (762, 128), (602, 268)]), fill=(255, 255, 255, 255))
    mask = disc.split()[3]
    head = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    head.paste(grad, (0, 0), mask)
    c2.img = head
    c2.d = ImageDraw.Draw(c2.img)
    # pale face heart: two overlapping ellipses
    c2.ellipse(452, 585, 148, 168, (224, 254, 250, 255))
    c2.ellipse(628, 585, 148, 168, (224, 254, 250, 255))
    c2.ellipse(540, 640, 150, 140, (224, 254, 250, 255))
    # eyes
    for ex in (448, 632):
        c2.ellipse(ex, 560, 86, 86, (255, 255, 255, 255))
        c2.ellipse(ex, 560, 56, 56, (8, 145, 178, 255))
        c2.ellipse(ex, 560, 27, 27, INK + (255,))
        c2.ellipse(ex + 14, 544, 10, 10, (255, 255, 255, 255))
    # beak
    c2.poly([(540, 700), (498, 648), (582, 648)], (245, 158, 11, 255))
    # brow tuft notch
    c2.poly([(540, 300), (505, 372), (575, 372)], (13, 148, 136, 255))
    return c2.finish(size_px)


# --------------------------------------------------------------------- B fox
def mascot_fox(size_px):
    c = Canvas(size_px)
    big = c.big
    s = c.s
    left = [(540, 318), (452, 268), (322, 128), (296, 392), (268, 520),
            (330, 700), (540, 902)]
    head_pts = left + mirror_x(left[:-1])
    grad = diagonal_gradient(big, (45, 212, 191), (14, 165, 198)).convert("RGBA")
    mask_img = Image.new("L", (big, big), 0)
    ImageDraw.Draw(mask_img).polygon(c.P(head_pts), fill=255)
    head = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    head.paste(grad, (0, 0), mask_img)
    c.img = head
    c.d = ImageDraw.Draw(c.img)
    # ear tips + inner ears (navy)
    c.poly([(322, 128), (296, 392), (408, 330)], INK + (255,))
    c.poly([(758, 128), (784, 392), (672, 330)], INK + (255,))
    c.poly([(330, 210), (322, 348), (382, 306)], (34, 211, 238, 255))
    c.poly([(750, 210), (758, 348), (698, 306)], (34, 211, 238, 255))
    # muzzle: pale lower face, merged into the chin point
    c.ellipse(540, 752, 158, 128, (236, 254, 255, 255))
    c.poly([(540, 902), (498, 852), (582, 852)], (236, 254, 255, 255))
    # nose
    c.poly([(540, 806), (500, 756), (580, 756)], INK + (255,))
    # sly eyes: wedges slanting up toward the ears
    c.poly([(368, 496), (492, 520), (490, 548), (372, 524)], INK + (255,))
    c.poly([(712, 496), (588, 520), (590, 548), (708, 524)], INK + (255,))
    return c.finish(size_px)


# --------------------------------------------------------------- C chameleon
def mascot_chameleon(size_px):
    c = Canvas(size_px)
    big = c.big
    s = c.s
    lime = (132, 204, 22)
    teal = (20, 184, 166)
    grad = diagonal_gradient(big, lime, teal).convert("RGBA")
    # body silhouette: casque head left, arched back, belly returning
    body = []
    body += cubic((238, 470), (250, 300), (360, 262), (430, 300))     # casque top
    body += cubic((430, 300), (560, 210), (760, 260), (838, 430))     # arched back
    body += cubic((838, 430), (880, 540), (820, 620), (716, 640))     # rear down
    body += cubic((716, 640), (560, 690), (400, 660), (300, 600))     # belly
    body += cubic((300, 600), (238, 560), (232, 520), (238, 470))     # throat
    mask_img = Image.new("L", (big, big), 0)
    ImageDraw.Draw(mask_img).polygon(c.P(body), fill=255)
    body_img = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    body_img.paste(grad, (0, 0), mask_img)
    c.img = body_img
    c.d = ImageDraw.Draw(c.img)
    # tail spiral (ribbon)
    cx, cy = 742, 742
    pts_out, pts_in = [], []
    for i in range(121):
        t = i / 120
        ang = -0.4 + t * 4.6 * math.pi
        r = 150 * (1 - t) + 16
        x, y = cx + r * math.cos(ang), cy + r * math.sin(ang) * 0.92
        wdt = 30 * (1 - t) + 7
        nx, ny = math.cos(ang), math.sin(ang)
        pts_out.append((x + nx * wdt, y + ny * wdt))
        pts_in.append((x - nx * wdt, y - ny * wdt))
    tail_grad = diagonal_gradient(big, teal, (13, 148, 136)).convert("RGBA")
    tail_mask = Image.new("L", (big, big), 0)
    ImageDraw.Draw(tail_mask).polygon(c.P(pts_out + pts_in[::-1]), fill=255)
    tail_img = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    tail_img.paste(tail_grad, (0, 0), tail_mask)
    c.img = Image.alpha_composite(c.img, tail_img)
    c.d = ImageDraw.Draw(c.img)
    # leg
    c.poly([(452, 640), (520, 640), (512, 742), (540, 742), (540, 782),
            (452, 782), (428, 700)], (13, 148, 136, 255))
    # spots
    for (sx, sy, sr) in [(560, 360, 34), (668, 420, 26), (500, 470, 22),
                         (730, 330, 20), (610, 540, 18)]:
        c.ellipse(sx, sy, sr, sr, (190, 242, 100, 110))
    # eye turret
    c.ellipse(352, 392, 58, 58, (101, 163, 13, 255))
    c.ellipse(352, 392, 34, 34, (217, 249, 157, 255))
    c.ellipse(366, 392, 15, 15, INK + (255,))
    # mouth line
    pts = cubic((240, 512), (320, 548), (400, 540), (452, 512), n=24)
    c.d.line(c.P(pts), fill=(4, 47, 46, 200), width=int(9 * s))
    return c.finish(size_px)


# ---------------------------------------------------------------- D meerkat
def mascot_meerkat(size_px):
    c = Canvas(size_px)
    big = c.big
    sand_hi = (217, 164, 65)
    sand_lo = (180, 113, 35)
    # shoulders
    grad = vertical_gradient(big, sand_hi, sand_lo).convert("RGBA")
    sh_mask = Image.new("L", (big, big), 0)
    ImageDraw.Draw(sh_mask).polygon(
        c.P(cubic((252, 1080), (280, 792), (400, 706), (540, 700)) +
            cubic((540, 700), (680, 706), (800, 792), (828, 1080))), fill=255)
    sh = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    sh.paste(grad, (0, 0), sh_mask)
    c.img = sh
    c.d = ImageDraw.Draw(c.img)
    # ears
    c.ellipse(298, 302, 68, 68, (146, 90, 32, 255))
    c.ellipse(782, 302, 68, 68, (146, 90, 32, 255))
    c.ellipse(298, 302, 34, 34, (217, 164, 65, 255))
    c.ellipse(782, 302, 34, 34, (217, 164, 65, 255))
    # head
    hg = vertical_gradient(big, (226, 176, 92), sand_lo).convert("RGBA")
    h_mask = Image.new("L", (big, big), 0)
    ImageDraw.Draw(h_mask).ellipse(
        [(540 - 252) * c.s, (470 - 272) * c.s, (540 + 252) * c.s, (470 + 272) * c.s], fill=255)
    head = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    head.paste(hg, (0, 0), h_mask)
    c.img = Image.alpha_composite(c.img, head)
    c.d = ImageDraw.Draw(c.img)
    # eye patches + eyes
    for ex in (452, 628):
        c.ellipse(ex, 446, 64, 82, (59, 42, 32, 255))
        c.ellipse(ex, 442, 28, 28, (255, 255, 255, 255))
        c.ellipse(ex, 442, 15, 15, (24, 16, 10, 255))
        c.ellipse(ex + 6, 434, 5, 5, (255, 255, 255, 255))
    # muzzle + nose + mouth
    c.ellipse(540, 636, 128, 92, (246, 231, 196, 255))
    c.ellipse(540, 590, 47, 27, (59, 42, 32, 255))
    pts = cubic((540, 618), (540, 654), (508, 670), (486, 658), n=16) + \
        cubic((540, 618), (540, 654), (572, 670), (594, 658), n=16)
    c.d.line(c.P(pts), fill=(59, 42, 32, 255), width=int(8 * c.s))
    return c.finish(size_px)


def make_background(size_px, glow=(45, 212, 191)):
    big = size_px * SS
    img = Image.new("RGB", (big, big))
    px = img.load()
    for y in range(big):
        col = lerp(NAVY_TOP, NAVY_DEEP, y / (big - 1))
        for x in range(size_px * SS):
            px[x, y] = col
    img = img.convert("RGBA")
    glow_img = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow_img)
    r = big * 0.36
    ctr = big / 2
    gd.ellipse([ctr - r, ctr - r * 1.05, ctr + r, ctr + r * 1.05], fill=glow + (55,))
    glow_img = glow_img.filter(ImageFilter.GaussianBlur(big * 0.09))
    img = Image.alpha_composite(img, glow_img)
    return img.resize((size_px, size_px), Image.LANCZOS)


def paste_center(base, mark, frac):
    m = mark.resize((int(base.width * frac), int(base.height * frac)), Image.LANCZOS)
    base.alpha_composite(m, ((base.width - m.width) // 2, (base.height - m.height) // 2))
    return base


MAKERS = {
    "a-owl": (mascot_owl, (45, 212, 191)),
    "b-fox": (mascot_fox, (45, 212, 191)),
    "c-chameleon": (mascot_chameleon, (132, 204, 22)),
    "d-meerkat": (mascot_meerkat, (217, 164, 65)),
}

if __name__ == "__main__":
    for name, (fn, glow) in MAKERS.items():
        mark = fn(864)
        mark.save(os.path.join(DESIGN, f"mascot-{name}-mark.png"))
        prev = make_background(512, glow)
        paste_center(prev, mark, 0.92)
        prev.save(os.path.join(DESIGN, f"mascot-{name}-preview.png"))
        print("wrote", name)
