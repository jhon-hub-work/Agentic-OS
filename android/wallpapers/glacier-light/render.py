"""Glacier light wallpaper for Agentic OS (no orb; the launcher draws Jharvis's lens on top).

Run: D:/Work/Claude/.venvs/sci/Scripts/python.exe render.py
Writes wallpaper_2160x4680.png and preview_1080x2340.png next to this file.
"""
import time
from pathlib import Path

import numpy as np
from PIL import Image

HERE = Path(__file__).resolve().parent
ORB = HERE.parent / "_orb_cutout_1080.png"
W, H = 2160, 4680            # 2x the S25 FE screen (1080x2340)
S = W / 1080                 # 4K pixels per screen pixel
CX, CY = 539 * S, 1052 * S   # orb centre
R = 197 * S                  # lens radius
rng = np.random.default_rng(20261101)


def hexf(h):
    return np.array([int(h[i:i + 2], 16) for i in (1, 3, 5)], np.float32) / 255


def smooth(a, b, x):
    t = np.clip((x - a) / (b - a), 0, 1)
    return t * t * (3 - 2 * t)


def lerp(c, col, w):
    """Blend image c toward colour col by weight map w (H, W)."""
    return c + (col - c) * w[..., None]


def noise(cells_x, seed):
    """Smooth value noise, about cells_x blobs across the width, range ~[-1, 1]."""
    g = np.random.default_rng(seed)
    cy = max(2, int(cells_x * H / W))
    small = g.standard_normal((cy + 3, cells_x + 3)).astype(np.float32)
    big = Image.fromarray(small, "F").resize((W + W // cells_x * 3, H + H // cy * 3), Image.BICUBIC)
    return np.asarray(big, np.float32)[:H, :W] / 1.2


def voronoi_edge(x, y, cell, seed):
    """Distance (px) to the nearest Voronoi cell border: F2 - F1 on a jittered grid."""
    g = np.random.default_rng(seed)
    n = 64
    jit = g.random((n, n, 2)).astype(np.float32)
    gx, gy = np.floor(x / cell).astype(np.int32), np.floor(y / cell).astype(np.int32)
    f1 = np.full(x.shape, 1e9, np.float32)
    f2 = f1.copy()
    for dj in (-1, 0, 1):
        for di in (-1, 0, 1):
            ix, iy = gx + di, gy + dj
            j = jit[iy % n, ix % n]
            px = (ix + 0.1 + 0.8 * j[..., 0]) * cell
            py = (iy + 0.1 + 0.8 * j[..., 1]) * cell
            d = np.hypot(x - px, y - py)
            f2 = np.where(d < f1, f1, np.minimum(f2, d))
            f1 = np.minimum(f1, d)
    return f2 - f1


def render():
    t0 = time.time()
    y, x = np.mgrid[0:H, 0:W].astype(np.float32)
    v = y / H                                # 0 top .. 1 bottom
    d = np.hypot(x - CX, y - CY) / R         # distance from orb centre, in lens radii

    # 1. Ice ground: near white at the top (dark glance text sits there), Glacier ground mid, cooler ice low.
    top, mid, low = hexf("#F6F9FC"), hexf("#E6EEF4"), hexf("#D5E4EF")
    c = lerp(np.broadcast_to(top, (H, W, 3)).copy(), mid, smooth(0.10, 0.48, v))
    c = lerp(c, low, smooth(0.50, 1.00, v))

    # 2. Daylight falling in from the upper left, cool shade toward the lower right.
    sun = np.exp(-(((x + 0.15 * W) / (1.1 * W)) ** 2 + ((y + 0.05 * H) / (0.42 * H)) ** 2))
    c = lerp(c, hexf("#FBFDFF"), 0.55 * sun)
    shade = smooth(0.35, 1.2, (x / W) * 0.6 + v * 0.7)
    c = lerp(c, hexf("#C9DCEA"), 0.35 * shade)

    # 3. Deeper blue pool that seats the dark lens; soft, so the shrunken orb at screen centre still sits in it.
    k = np.maximum(d - 0.85, 0)
    pool = 0.62 * np.exp(-(k / 1.15) ** 2) + 0.28 * np.exp(-k / 2.4)
    c = lerp(c, hexf("#8DB8D6"), np.clip(pool, 0, 1) * 0.85)
    c = lerp(c, hexf("#6FA5CB"), 0.30 * np.exp(-(k / 0.55) ** 2))      # a touch deeper right under the halo

    # 4. Caustics: sunlight through glacier ice. Two warped Voronoi line nets, brightest where they cross.
    # medium-scale warp bends the straight Voronoi borders into curved water-caustic cells
    wx = noise(5, 1) * 70 * S + noise(11, 2) * 34 * S + noise(24, 7) * 9 * S
    wy = noise(5, 3) * 70 * S + noise(11, 4) * 34 * S + noise(24, 8) * 9 * S
    e1 = voronoi_edge(x + wx, y + wy, 150 * S, 11)
    e2 = voronoi_edge(x * 0.93 - wy * 0.8 + 400, y * 0.93 + wx * 0.8 + 900, 110 * S, 12)
    lw = 2.6 * S * (1 + 0.6 * (noise(9, 5) * 0.5 + 0.5))                 # line width breathes
    l1, l2 = np.exp(-(e1 / lw) ** 2), np.exp(-(e2 / (lw * 0.8)) ** 2)
    caus = 0.55 * l1 + 0.35 * l2 + 0.9 * l1 * l2
    caus += 0.25 * np.exp(-(e1 / (lw * 4)) ** 2)                          # soft bloom around the lines
    patch = smooth(-0.2, 0.7, noise(4, 6)) * (0.45 + 0.55 * smooth(-0.6, 0.6, noise(16, 9)))  # patches + lines fading in and out
    # Calm zones: glance text (0-22%), under-orb line/pill (55-65%), dock (84-97%); quiet ring round the lens.
    zone = 0.12 + 0.88 * smooth(0.18, 0.30, v)
    zone *= 1 - 0.55 * np.exp(-((v - 0.60) / 0.05) ** 2)
    zone *= 1 - 0.65 * smooth(0.80, 0.86, v)
    zone *= smooth(1.2, 2.6, d) * 0.75 + 0.25
    c = lerp(c, hexf("#F4FBFF"), np.clip(caus * patch * zone * 0.42, 0, 1))
    # matching faint refraction shadow just beside each bright line
    sh = np.exp(-((e1 - 3.2 * lw) / (1.6 * lw)) ** 2) * patch * zone
    c = lerp(c, hexf("#9DBFD8"), np.clip(sh * 0.10, 0, 1))

    # 5. Frost grain (fine, slightly soft) + triangular dither before quantizing so gradients never band.
    grain = np.asarray(Image.fromarray(rng.standard_normal((H, W)).astype(np.float32), "F")
                       .resize((W // 2, H // 2), Image.BILINEAR).resize((W, H), Image.BICUBIC), np.float32)
    c += (grain * 1.6 / 255)[..., None]
    tpdf = (rng.random((H, W, 3), np.float32) + rng.random((H, W, 3), np.float32) - 1) * 1.5 / 255
    out = np.clip(np.round((c + tpdf) * 255), 0, 255).astype(np.uint8)

    wall = Image.fromarray(out, "RGB")
    wall.save(HERE / "wallpaper_2160x4680.png", optimize=True)
    t1 = time.time()

    # Preview: downscale, add the app's halo approximation, paste the real orb.
    p = np.asarray(wall.resize((1080, 2340), Image.LANCZOS), np.float32) / 255
    yy, xx = np.mgrid[0:2340, 0:1080].astype(np.float32)
    dd = np.hypot(xx - 539, yy - 1052)
    halo = 0.4 * (1 - smooth(197, 197 * 1.55, dd)) * (dd >= 190)
    p = np.clip(p + hexf("#3FB6EC") * halo[..., None], 0, 1)
    prev = Image.fromarray(np.round(p * 255).astype(np.uint8), "RGB")
    orb = Image.open(ORB).convert("RGBA")
    a = np.asarray(orb, np.float32)
    oy, ox = np.mgrid[0:406, 0:406].astype(np.float32)
    # trim the screenshot's background fringe outside the 197 px rim
    a[..., 3] *= np.clip(199.5 - np.hypot(ox - 202.5, oy - 202.5), 0, 1)
    orb = Image.fromarray(a.astype(np.uint8), "RGBA")
    prev.paste(orb, (539 - 203, 1052 - 203), orb)
    prev.save(HERE / "preview_1080x2340.png", optimize=True)
    print(f"wallpaper {t1 - t0:.1f}s, total {time.time() - t0:.1f}s")


if __name__ == "__main__":
    render()
