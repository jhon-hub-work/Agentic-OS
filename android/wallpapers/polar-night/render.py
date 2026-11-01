"""Polar night wallpaper for Agentic OS (Night Glacier). No orb: the launcher draws it live.
Run: D:/Work/Claude/.venvs/sci/Scripts/python.exe render.py
Makes wallpaper_2160x4680.png and preview_1080x2340.png (real orb cutout + approx halo) next to this file."""
import time
from pathlib import Path
import numpy as np
from PIL import Image

T0 = time.time()
HERE = Path(__file__).resolve().parent
ORB_CUTOUT = HERE.parent / "_orb_cutout_1080.png"
W, H = 2160, 4680
S = W / 1080                      # 4K px per screen px
OX, OY, OR = 539 * S, 1052 * S, 197 * S   # orb spot (centre, lens radius) in 4K px
rng = np.random.default_rng(7)


def lin(hexcol):                  # sRGB hex -> linear float rgb
    c = np.array([int(hexcol[i:i + 2], 16) for i in (1, 3, 5)], np.float32) / 255
    return np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)


def to_srgb(x):
    x = np.clip(x, 0, 1)
    return np.where(x <= 0.0031308, x * 12.92, 1.055 * x ** (1 / 2.4) - 0.055)


def smooth_noise(gw, gh, seed):   # low-frequency value noise, bicubic-upsampled to full size (no tiling)
    g = np.random.default_rng(seed).random((gh, gw)).astype(np.float32)
    return np.asarray(Image.fromarray(g, "F").resize((W, H), Image.BICUBIC))


def noise1d(n, x, seed):          # smooth 1-D noise along x (0..1), n control points
    p = np.random.default_rng(seed).random(n + 3)
    t = x * n
    i = np.floor(t).astype(int)
    f = t - i
    a, b, c, d = p[i], p[i + 1], p[i + 2], p[i + 3]   # Catmull-Rom
    return np.clip(b + 0.5 * f * (c - a + f * (2 * a - 5 * b + 4 * c - d + f * (3 * (b - c) + d - a))), 0, 1)


def sstep(a, b, x):
    t = np.clip((x - a) / (b - a), 0, 1)
    return t * t * (3 - 2 * t)


yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
u, v = xx / W, yy / H
dist = np.hypot(xx - OX, yy - OY) / OR          # distance from orb centre in lens radii

# --- 1. ground: Night Glacier navy, darkest at the top (light text zone), lifted around the orb
top, mid, low = lin("#04080F"), lin("#070D17"), lin("#09111D")
k = sstep(0.0, 0.55, v)[..., None]
img = top * (1 - k) + mid * k
k2 = sstep(0.55, 1.0, v)[..., None]
img = img * (1 - k2) + low * k2
# soft navy pool the orb sits in (mesh colours #16233A / #0E2A3D)
pool = np.exp(-(dist / 4.2) ** 2)[..., None]
img = img + (lin("#11213A") - lin("#070D17")) * pool * 0.5
img = img + lin("#0E2A3D") * (np.exp(-(dist / 2.4) ** 2) * 0.25)[..., None]
# gentle large-scale variation so it never reads as a flat CG gradient
img = img * (0.93 + 0.14 * smooth_noise(5, 9, 1))[..., None]

# --- 2. aurora ribbons: sharp lower edge, long soft upward fade, slow vertical rays
def ribbon(edge, width_up, width_lo, strength, rays_seed, col_core, col_top, mask):
    d = (yy / H - edge) * H / W                 # signed distance in screen widths (+ = below edge)
    up = np.exp(-np.abs(np.minimum(d, 0)) / width_up)
    lo = np.exp(-(np.maximum(d, 0) / width_lo) ** 2)
    prof = np.where(d < 0, up, lo)
    # rays: slightly slanted curtain folds, strongest near the edge
    slant = u + 0.10 * (v - 0.5)
    rays = 0.55 + 0.45 * noise1d(46, np.clip(slant * 0.8 + 0.1, 0, 0.999), rays_seed)
    fine = 0.8 + 0.2 * noise1d(150, np.clip(slant * 0.8 + 0.1, 0, 0.999), rays_seed + 1)
    near = np.exp(-np.abs(d) / (width_up * 0.6))
    prof = prof * (1 - near + near * rays * fine)
    along = 0.45 + 0.55 * noise1d(5, np.clip(u * 0.9 + 0.05, 0, 0.999), rays_seed + 2)
    a = (prof * along * mask * strength)[..., None]
    hue = np.exp(-np.abs(np.minimum(d, 0)) / (width_up * 0.7))[..., None]   # core near edge, deep blue higher up
    return a * (col_core * hue + col_top * (1 - hue))


calm_top = sstep(0.20, 0.34, v)                  # nothing in the glance zone
calm_dock = 1 - 0.65 * sstep(0.80, 0.86, v)      # dock stays quiet
glacier, deep, teal = lin("#3FB6EC"), lin("#2672A2"), lin("#22A39A")

# A: main ribbon, arcs under the orb from lower-left up to the right edge
edgeA = 0.715 - 0.20 * u + 0.035 * np.sin(u * 5.2 + 0.6) + 0.03 * (u - 0.45) ** 2 * 4
img += ribbon(edgeA, 0.085, 0.035, 0.36, 11, glacier * 0.9 + teal * 0.1, deep, calm_top * calm_dock)
# B: thinner, fainter, high on the right, fading out before the left half (glance zone)
edgeB = 0.395 - 0.07 * u + 0.02 * np.sin(u * 7.0 + 2.0)
img += ribbon(edgeB, 0.06, 0.025, 0.17, 23, glacier, deep * 0.8, calm_top * sstep(0.30, 0.75, u))
# C: very faint low wash on the left, gives depth behind the dock
edgeC = 0.80 + 0.03 * u
img += ribbon(edgeC, 0.10, 0.05, 0.05, 37, deep, deep * 0.5, sstep(0.5, 0.0, u) * 0.8 + 0.2)

# the orb glows in the night: wide, faint Glacier bloom beyond the app's own halo (starts past 1.9 R)
img += glacier * (np.exp(-(np.maximum(dist - 1.0, 0) / 3.0) ** 1.6) * 0.035)[..., None]

# --- 3. sparse star / dot field, densest near the orb like its dot sphere spilling out
N = 1400
sx, sy = rng.random(N) * W, rng.random(N) * H
sd = np.hypot(sx - OX, sy - OY) / OR
keep = rng.random(N) < (0.18 + 0.82 * np.exp(-((sd - 1.0) / 3.5) ** 2))
sv = sy / H
keep &= ~((sv < 0.23) & (sx < 0.78 * W) & (rng.random(N) < 0.85))   # glance zone: almost empty
keep &= ~((sv > 0.83) & (rng.random(N) < 0.8))                       # dock: almost empty
keep &= sd > 1.05                                                    # under the lens is hidden anyway
sx, sy, sd = sx[keep], sy[keep], sd[keep]
n = len(sx)
bright = rng.pareto(2.2, n) * 0.08 + 0.05
bright = np.minimum(bright, 0.9)
sigma = (0.55 + 0.6 * rng.random(n) ** 3) * S + bright * 1.2 * S
palette = np.stack([lin("#D8EAF8"), lin("#9FDDF7"), lin("#3FB6EC")])
cols = palette[rng.choice(3, n, p=[0.45, 0.35, 0.20])]
stars = np.zeros_like(img)
for x0, y0, s, b, c in zip(sx, sy, sigma, bright, cols):
    r = int(np.ceil(s * 4)) + 1
    xa, xb = max(int(x0) - r, 0), min(int(x0) + r + 1, W)
    ya, yb = max(int(y0) - r, 0), min(int(y0) + r + 1, H)
    gx = np.arange(xa, xb) + 0.5 - x0
    gy = np.arange(ya, yb) + 0.5 - y0
    g = np.exp(-(gy[:, None] ** 2 + gx[None, :] ** 2) / (2 * s * s))
    stars[ya:yb, xa:xb] += g[..., None] * c * b
img += stars

# --- 4. vignette, quantize with triangular dither (no banding)
vig = 1 - 0.22 * sstep(0.55, 1.25, np.hypot((u - 0.5) * 1.2, (v - 0.47) * 0.75) * 1.6)
img *= vig[..., None]
out = to_srgb(img) * 255 + (rng.random(img.shape, np.float32) + rng.random(img.shape, np.float32) - 1) * 1.5
wall = Image.fromarray(np.clip(np.rint(out), 0, 255).astype(np.uint8), "RGB")
wall.save(HERE / "wallpaper_2160x4680.png", optimize=False)
print(f"wallpaper: {time.time() - T0:.1f}s, {n} stars")

# --- preview: downscale + app halo approximation + real orb cutout
pv = np.asarray(wall.resize((1080, 2340), Image.LANCZOS)).astype(np.float32) / 255
py, px = np.mgrid[0:2340, 0:1080].astype(np.float32)
dr = np.hypot(px - 539, py - 1052) / 197
t = np.clip((dr - 1) / 0.55, 0, 1)
halo = np.where(dr >= 1, 0.4 * (1 - t) ** 2, 0)[..., None]
pv = np.clip(pv + halo * np.array([0x3F, 0xB6, 0xEC], np.float32) / 255, 0, 1)
prev = Image.fromarray(np.rint(pv * 255).astype(np.uint8), "RGB")
orb = Image.open(ORB_CUTOUT).convert("RGBA")
prev.paste(orb, (539 - orb.width // 2, 1052 - orb.height // 2), orb)
prev.save(HERE / "preview_1080x2340.png")
print(f"done: {time.time() - T0:.1f}s")
