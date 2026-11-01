"""Dot wave: ice-light 4K wallpaper for Agentic OS, composed around Jharvis's orb (orb not drawn).

Makes wallpaper_2160x4680.png and preview_1080x2340.png (real orb cutout + approx halo pasted on).
Run: D:/Work/Claude/.venvs/sci/Scripts/python.exe render.py
"""
import os
import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
W, H = 2160, 4680
S = 2.0                                   # 4K pixels per phone pixel
OCX, OCY, OR = 539 * S, 1052 * S, 197 * S  # orb spot on the 4K canvas
rng = np.random.default_rng(7)


def hexc(h):
    return np.array([int(h[i:i + 2], 16) for i in (1, 3, 5)], np.float32) / 255


def mix(a, b, t):
    return a + (b - a) * t


def smooth(t):
    t = np.clip(t, 0, 1)
    return t * t * (3 - 2 * t)


# Glacier theme tokens (Themes.kt)
GROUND, WHITE, MESH1, MESH2 = hexc("#E6EEF4"), hexc("#FFFFFF"), hexc("#CFE3F2"), hexc("#DCE8F1")
GLACIER, NAVY, SLATE = hexc("#3FB6EC"), hexc("#1F2D44"), hexc("#5E6D7B")   # accent, navy, ink3

ys, xs = np.mgrid[0:H, 0:W].astype(np.float32)
v = ys / H


def blob(cx, cy, rx, ry):
    """Soft elliptical falloff, 1 at centre, 0 at the rim."""
    d = np.sqrt(((xs - cx) / rx) ** 2 + ((ys - cy) / ry) ** 2)
    return smooth(1 - d)


# --- ground: pale at the top (dark text zone), ground through the middle, a touch cooler at the bottom
img = np.empty((H, W, 3), np.float32)
top, mid, low = mix(GROUND, WHITE, 0.62), mix(GROUND, WHITE, 0.25), mix(GROUND, MESH2, 0.55)
t1 = smooth(v / 0.5)[..., None]
t2 = smooth((v - 0.5) / 0.5)[..., None]
img[:] = mix(mix(top, mid, t1), low, t2)

# white bloom behind the glance block (top left), keeps the date/weather text on near-white
img = mix(img, WHITE, (0.55 * blob(W * 0.22, H * 0.15, W * 0.95, H * 0.16))[..., None])
# the orb's cradle: a cool ice pool so the dark lens sits in light, not on a flat sheet
img = mix(img, MESH1, (0.75 * blob(OCX, OCY, OR * 3.4, OR * 3.4) ** 1.3)[..., None])
img = mix(img, mix(MESH1, GLACIER, 0.18), (0.45 * blob(OCX, OCY, OR * 2.0, OR * 2.0) ** 1.6)[..., None])
# a faint Glacier wash where the wave lives, as if the orb's light falls on the field
img = mix(img, mix(MESH1, GLACIER, 0.12), (0.5 * blob(W * 0.5, H * 0.72, W * 0.85, H * 0.13))[..., None])


def over(dst, color, a):
    """Composite color at per-pixel alpha a (H,W) over dst."""
    dst += (color - dst) * a[..., None]


# --- hairline orbit rings around the orb spot, lit from the upper left like the lens's specular arc
r = np.hypot(xs - OCX, ys - OCY)
ang = np.arctan2(ys - OCY, xs - OCX)
light = 0.5 + 0.5 * np.cos(ang - np.arctan2(-1, -1))       # 1 toward the upper left
for k, (mult, w, a) in enumerate([(1.25, 2.4, 0.20), (1.9, 2.2, 0.16), (2.6, 2.0, 0.12)]):
    rr = OR * mult
    cov = np.clip(w / 2 - np.abs(r - rr) + 0.5, 0, 1)      # analytic AA hairline
    over(img, mix(GLACIER, NAVY, 0.25), cov * a * (0.35 + 0.65 * light))

# --- the dot wave: one static frame of WaveMath, in perspective across the lower middle
COLS, ROWS, HALF_W, DEPTH, NEAR, CAM_H = 44, 24, 11.0, 12.0, 5.0, 5.0
T = 1.3                                                      # the frame's phase
NEAR_Y, FAR_Y = 0.815 * H, 0.635 * H                         # flat-ground screen y of nearest / farthest rows
f = (NEAR_Y - FAR_Y) / (CAM_H / NEAR - CAM_H / (DEPTH + NEAR))
horizon = NEAR_Y - CAM_H * f / NEAR
fx = 0.62 * W * NEAR / HALF_W                               # across: the nearest row just overfills the screen, so the full slate-to-navy row shows
pale = mix(GLACIER, GROUND, 0.75)
stops_u = np.array([-0.12, 0.27, 0.5, 0.73, 1.12])           # slate -> Glacier -> navy across the screen


def height(x, z, t, amp=1.0):
    return (np.sin(x * 0.55 + t * 0.9) * 0.35 + np.sin(x * 1.3 - t * 1.4 + z * 0.8) * 0.18 * amp
            + np.cos(z * 1.1 + t * 0.6) * 0.12) * (0.75 + amp * 0.25) * 1.8


def wave_colour(u, far):
    tone = lambda c: mix(c, pale, far * 0.7)
    cols = [tone(SLATE), tone(mix(SLATE, GLACIER, 0.6)), tone(GLACIER), tone(mix(GLACIER, NAVY, 0.4)), tone(NAVY)]
    alphas = [0, 1, 1, 1, 0]
    i = int(np.clip(np.searchsorted(stops_u, u) - 1, 0, 3))
    t = float(np.clip((u - stops_u[i]) / (stops_u[i + 1] - stops_u[i]), 0, 1))
    return mix(cols[i], cols[i + 1], t), mix(alphas[i], alphas[i + 1], t)


BASE_R = 8.5                                                 # nearest dot radius at 4K
for row in range(ROWS - 1, -1, -1):                          # far rows first, near dots land on top
    z = row / (ROWS - 1) * DEPTH
    far = row / (ROWS - 1)
    row_a = (0.45 + 0.55 * (1 - far)) * smooth(min(far, 1 - far) / 0.2)
    rad = max(BASE_R * NEAR / (z + NEAR), 2.2)
    for col in range(COLS):
        x = (col / (COLS - 1) - 0.5) * 2 * HALF_W
        sx = W / 2 + x * fx / (z + NEAR)
        sy = horizon + (CAM_H - height(x, z, T)) * f / (z + NEAR)
        if not (-10 < sx < W + 10):
            continue
        c, ca = wave_colour(sx / W, far)
        # keep the field inside 60-84 %: fade in under the "Nothing needs you" line, out before the dock
        vy = sy / H
        zone = smooth((vy - 0.60) / 0.06) * (1 - smooth((vy - 0.785) / 0.05))
        a = 0.85 * row_a * ca * zone
        if a < 0.004:
            continue
        x0, x1 = int(sx - rad - 2), int(sx + rad + 3)
        y0, y1 = int(sy - rad - 2), int(sy + rad + 3)
        x0, y0, x1, y1 = max(x0, 0), max(y0, 0), min(x1, W), min(y1, H)
        if x0 >= x1 or y0 >= y1:
            continue
        d = np.hypot(xs[y0:y1, x0:x1] - sx, ys[y0:y1, x0:x1] - sy)
        cov = np.clip(rad - d + 0.5, 0, 1)                    # analytic AA disc
        patch = img[y0:y1, x0:x1]
        patch += (c - patch) * (cov * a)[..., None]

# --- dither (triangular, +-1.5/255) and quantise
noise = (rng.random((H, W, 1), dtype=np.float32) + rng.random((H, W, 1), dtype=np.float32) - 1) * (1.5 / 255)
out = np.clip(np.round((img + noise) * 255), 0, 255).astype(np.uint8)
wall = Image.fromarray(out, "RGB")
wall.save(os.path.join(HERE, "wallpaper_2160x4680.png"), optimize=True)

# --- preview: downscale, approximate the app's halo, paste the real orb cutout
pv = np.asarray(wall.resize((1080, 2340), Image.LANCZOS), np.float32) / 255
py, px = np.mgrid[0:2340, 0:1080].astype(np.float32)
pr = np.hypot(px - 539, py - 1052)
t = np.clip((pr - 197) / (197 * 0.55), 0, 1)
halo = np.where(pr >= 197, 0.4 * (1 - t) ** 2, 0.4)
pv = np.clip(pv + GLACIER * halo[..., None], 0, 1)          # additive like the app's glow
prev = Image.fromarray(np.round(pv * 255).astype(np.uint8), "RGB")
orb = np.asarray(Image.open(os.path.join(HERE, "..", "_orb_cutout_1080.png")).convert("RGBA"), np.float32)
oy, ox = np.mgrid[0:orb.shape[0], 0:orb.shape[1]].astype(np.float32)
c0 = (orb.shape[0] - 1) / 2
mask = np.clip(199.5 - np.hypot(ox - c0, oy - c0), 0, 1)     # drop the screenshot's background bleed at the rim
orb[..., 3] = orb[..., 3] * mask
orb_img = Image.fromarray(np.round(orb).astype(np.uint8), "RGBA")
prev.paste(orb_img, (int(539 - c0 - 0.5), int(1052 - c0 - 0.5)), orb_img)
prev.save(os.path.join(HERE, "preview_1080x2340.png"), optimize=True)

assert wall.size == (W, H) and prev.size == (1080, 2340)
print("ok", f"f={f:.0f} horizon={horizon:.0f}")
