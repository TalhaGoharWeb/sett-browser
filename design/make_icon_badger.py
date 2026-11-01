#!/usr/bin/env python3
"""Build the Private X Android icon set from the chosen AI badger concept art.

Source: design/ai-concepts/media-generation-concept-badger-*.webp
(the mascot Talha picked on 2026-10-31).

Approach: the concept art is a character bust inside a navy disc on a white
field. We flood everything outside the disc with the disc's own navy so the
square is seamless (navy corners == adaptive background layer), crop with a
small navy margin around the disc, and derive:
  - adaptive foreground (the art) and background (flat navy) per density
  - legacy ic_launcher per density
  - a monochrome silhouette: pixels whose hue is far from the disc navy
    (teal/cream character) become white; the navy disc stays transparent.
"""
import colorsys
import glob
import numpy as np
from PIL import Image, ImageDraw, ImageFilter

SRC = sorted(glob.glob('/home/hatch/workspace/private-x/design/ai-concepts/'
                       'media-generation-concept-badger-0-*.webp'))[0]
RES = '/home/hatch/workspace/private-x/android/app/src/main/res'
OUT_PREVIEW = '/home/hatch/workspace/private-x/design'

src = Image.open(SRC).convert('RGB')
W, H = src.size
a = np.asarray(src).astype(int)

# --- locate the disc: anything that is not near-white -----------------------
nonwhite = (a.min(axis=2) < 240)
ys, xs = np.nonzero(nonwhite)
x0, x1, y0, y1 = xs.min(), xs.max(), ys.min(), ys.max()
cx, cy = (x0 + x1) / 2.0, (y0 + y1) / 2.0
r = ((x1 - x0) + (y1 - y0)) / 4.0
print(f'disc bbox x[{x0},{x1}] y[{y0},{y1}] centre=({cx:.0f},{cy:.0f}) r={r:.0f}')

# --- sample the disc navy (median of an annulus near the rim) ---------------
yy, xx = np.mgrid[0:H, 0:W]
dist = np.sqrt((xx - cx) ** 2 + (yy - cy) ** 2)
annulus = nonwhite & (dist > 0.80 * r) & (dist < 0.93 * r)
navy = tuple(int(v) for v in np.median(a[annulus], axis=0))
print('navy sample:', navy)

# --- master art: navy square, disc art pasted through an eroded mask --------
mask = Image.fromarray((dist <= r).astype(np.uint8) * 255)
mask = mask.filter(ImageFilter.MinFilter(5))          # eat the light rim
master = Image.new('RGB', (W, H), navy)
master.paste(src, (0, 0), mask)

# the source disc carries a thin light rim stroke at its edge; force the
# outer band of the disc to flat navy so no ring survives into the icon
_band = np.asarray(master).copy()
_band[dist >= r - 10] = navy
master = Image.fromarray(_band)

# --- flatten the background: every non-character pixel becomes flat navy ----
# (the source disc has a soft radial gradient; a logo background must be flat)
def char_mask(rgb_arr):
    m = rgb_arr.astype(float) / 255.0
    mx, mn = m.max(axis=2), m.min(axis=2)
    diff = mx - mn
    hue = np.zeros(mx.shape)
    nz = diff > 1e-6
    rr, gg, bb = m[:, :, 0], m[:, :, 1], m[:, :, 2]
    sel = nz & (mx == rr)
    hue[sel] = ((gg - bb)[sel] / diff[sel]) % 6
    sel = nz & (mx == gg)
    hue[sel] = (bb - rr)[sel] / diff[sel] + 2
    sel = nz & (mx == bb)
    hue[sel] = (rr - gg)[sel] / diff[sel] + 4
    hue *= 60.0
    nr, ng, nb = (c / 255.0 for c in navy)
    navy_h, _, navy_v = colorsys.rgb_to_hsv(nr, ng, nb)
    hdiff = np.abs(hue - navy_h * 360.0)
    hdiff = np.minimum(hdiff, 360.0 - hdiff)
    sat = np.where(mx > 0, diff / np.maximum(mx, 1e-9), 0)
    return ((hdiff > 28) & (sat > 0.18)) | (mx > navy_v + 0.28)

flat = np.asarray(master).copy()
flat[~char_mask(flat)] = navy
master = Image.fromarray(flat)

# crop: square around the disc with ~5% navy margin
side = int(2 * r * 1.10)
left = int(min(max(cx - side / 2, 0), W - side))
top = int(min(max(cy - side / 2, 0), H - side))
master = master.crop((left, top, left + side, top + side)).resize(
    (1024, 1024), Image.LANCZOS)
master.save(f'{OUT_PREVIEW}/badger-icon-master.png')

# --- monochrome silhouette: hue far from navy, or much brighter -------------
nr, ng, nb = (c / 255.0 for c in navy)
navy_h, _, navy_v = colorsys.rgb_to_hsv(nr, ng, nb)
m = np.asarray(master).astype(float) / 255.0
mx, mn = m.max(axis=2), m.min(axis=2)
diff = mx - mn
hue = np.zeros(mx.shape)
nz = diff > 1e-6
rr, gg, bb = m[:, :, 0], m[:, :, 1], m[:, :, 2]
sel = nz & (mx == rr)
hue[sel] = ((gg - bb)[sel] / diff[sel]) % 6
sel = nz & (mx == gg)
hue[sel] = (bb - rr)[sel] / diff[sel] + 2
sel = nz & (mx == bb)
hue[sel] = (rr - gg)[sel] / diff[sel] + 4
hue *= 60.0
hdiff = np.abs(hue - navy_h * 360.0)
hdiff = np.minimum(hdiff, 360.0 - hdiff)
sat = np.where(mx > 0, diff / np.maximum(mx, 1e-9), 0)
char = ((hdiff > 28) & (sat > 0.18)) | (mx > navy_v + 0.28)
char_im = Image.fromarray(char.astype(np.uint8) * 255)
char_im = char_im.filter(ImageFilter.MedianFilter(9)).filter(
    ImageFilter.MaxFilter(5))
mono = Image.new('RGBA', (1024, 1024), (255, 255, 255, 0))
mono.putalpha(char_im)
mono.save(f'{OUT_PREVIEW}/badger-icon-mono.png')

# --- write the Android set ----------------------------------------------------
dens = {'mdpi': 1.0, 'hdpi': 1.5, 'xhdpi': 2.0, 'xxhdpi': 3.0, 'xxxhdpi': 4.0}
for name, s in dens.items():
    d = f'{RES}/mipmap-{name}'
    master.resize((round(108 * s),) * 2, Image.LANCZOS).save(
        f'{d}/ic_launcher_foreground.png')
    Image.new('RGB', (round(108 * s),) * 2, navy).save(
        f'{d}/ic_launcher_background.png')
    master.resize((round(48 * s),) * 2, Image.LANCZOS).save(
        f'{d}/ic_launcher.png')
mono.resize((432, 432), Image.LANCZOS).save(
    f'{RES}/drawable/ic_launcher_monochrome.png')

# --- previews -----------------------------------------------------------------
prev = master.resize((512, 512), Image.LANCZOS).convert('RGBA')
corner = Image.new('L', (512, 512), 0)
ImageDraw.Draw(corner).rounded_rectangle([0, 0, 512, 512], 114, fill=255)
bg = Image.new('RGBA', (512, 512), (255, 255, 255, 255))
bg.paste(prev, (0, 0), corner)
bg.convert('RGB').save(f'{OUT_PREVIEW}/badger-icon-preview.png')

mp = Image.new('RGBA', (512, 512), (24, 24, 32, 255))
m512 = mono.resize((512, 512), Image.LANCZOS)
mp.paste(m512, (0, 0), m512)
mp.convert('RGB').save(f'{OUT_PREVIEW}/badger-icon-mono-preview.png')
print('icon set written')
