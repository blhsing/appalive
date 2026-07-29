#!/usr/bin/env python3
"""Generate AppAlive's icons with no third-party dependencies.

The mark is a shield with an ECG pulse knocked out of it: protected, and alive.
Knocking the pulse out rather than drawing it on top keeps exactly two tones,
which is what survives being scaled down to a 24dp status-bar glyph.

Notification icons must be PNG: VectorDrawable cannot be inflated inside
RemoteViews below API 24, and minSdk is 21. Rather than commit opaque binaries
we draw them here with a small supersampled rasterizer (stdlib only).

    python tools/make_icons.py

Writes:
  app/res/drawable-*/ic_stat_alive.png          white silhouette, transparent
  app/res/mipmap-*/ic_launcher.png              legacy launcher icon
  app/res/mipmap-*/ic_launcher_foreground.png   adaptive-icon foreground (API 26+)
"""

import math
import os
import struct
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(os.path.dirname(HERE), "app", "res")

SS = 4  # supersampling factor, for antialiasing

BG = (0x10, 0x33, 0x3F, 0xFF)   # dark slate
FG = (0x34, 0xD3, 0x99, 0xFF)   # "alive" green
WHITE = (0xFF, 0xFF, 0xFF, 0xFF)
CLEAR = (0, 0, 0, 0)

# --- shield geometry, in unit coordinates (0..1, y down) --------------------
SHIELD_TOP = 0.06
SHIELD_TIP = 0.97
SHIELD_HALF_W = 0.37
SHIELD_SHOULDER = 0.50   # y below which the sides start converging
SHIELD_CORNER = 0.11     # top corner rounding radius

# An ECG trace sized to sit inside the shield's widest band.
PULSE = [
    (0.20, 0.45), (0.33, 0.45), (0.40, 0.29), (0.50, 0.63),
    (0.57, 0.39), (0.64, 0.45), (0.80, 0.45),
]

# A status-bar icon is always drawn at 24dp no matter which density bucket it
# came from, so it gets a simplified trace at every size: the secondary bump
# above closes up and reads as noise once the stroke is ~2px.
PULSE_SIMPLE = [
    (0.17, 0.46), (0.36, 0.46), (0.45, 0.27), (0.56, 0.65),
    (0.65, 0.46), (0.83, 0.46),
]


def shield_half_width(y):
    """Half-width of the shield at height y, or None if outside it."""
    if y < SHIELD_TOP or y > SHIELD_TIP:
        return None
    if y <= SHIELD_SHOULDER:
        # straight sides, with the top corners rounded off
        if y < SHIELD_TOP + SHIELD_CORNER:
            dy = (SHIELD_TOP + SHIELD_CORNER) - y
            inset = SHIELD_CORNER - math.sqrt(max(0.0, SHIELD_CORNER ** 2 - dy ** 2))
            return SHIELD_HALF_W - inset
        return SHIELD_HALF_W
    # below the shoulder the sides bulge outward slightly, then meet at a point
    t = (y - SHIELD_SHOULDER) / (SHIELD_TIP - SHIELD_SHOULDER)
    return SHIELD_HALF_W * (max(0.0, 1.0 - t * t) ** 0.62)


def blend(dst, src):
    """Source-over alpha composite of two RGBA tuples."""
    sa = src[3] / 255.0
    if sa <= 0:
        return dst
    if sa >= 1:
        return src
    da = dst[3] / 255.0
    oa = sa + da * (1 - sa)
    if oa <= 0:
        return CLEAR
    out = [int(round((src[i] * sa + dst[i] * da * (1 - sa)) / oa)) for i in range(3)]
    return (out[0], out[1], out[2], int(round(oa * 255)))


def new_canvas(n):
    return [[CLEAR] * n for _ in range(n)]


def fill_round_rect(buf, n, inset, radius, color):
    x0 = y0 = inset
    x1 = y1 = n - inset
    for y in range(n):
        for x in range(n):
            cx = min(max(x + 0.5, x0 + radius), x1 - radius)
            cy = min(max(y + 0.5, y0 + radius), y1 - radius)
            dx = (x + 0.5) - cx
            dy = (y + 0.5) - cy
            if dx * dx + dy * dy <= radius * radius:
                buf[y][x] = blend(buf[y][x], color)


def fill_shield(buf, n, box, color):
    """box = (ox, oy, size) placing the unit square onto the canvas."""
    ox, oy, size = box
    lo = max(0, int(oy + SHIELD_TOP * size) - 2)
    hi = min(n, int(oy + SHIELD_TIP * size) + 2)
    for py in range(lo, hi):
        uy = ((py + 0.5) - oy) / size
        hw = shield_half_width(uy)
        if hw is None:
            continue
        x_lo = max(0, int(ox + (0.5 - hw) * size))
        x_hi = min(n, int(ox + (0.5 + hw) * size) + 1)
        for px in range(x_lo, x_hi):
            ux = ((px + 0.5) - ox) / size
            if abs(ux - 0.5) <= hw:
                buf[py][px] = blend(buf[py][px], color)


def stroke_polyline(buf, n, box, pts, width, color):
    """Round-capped polyline. color=None erases (used to knock out the pulse)."""
    ox, oy, size = box
    half = width * size / 2.0
    px_pts = [(ox + x * size, oy + y * size) for (x, y) in pts]
    for i in range(len(px_pts) - 1):
        ax, ay = px_pts[i]
        bx, by = px_pts[i + 1]
        vx, vy = bx - ax, by - ay
        seg2 = vx * vx + vy * vy
        lo_x = max(0, int(min(ax, bx) - half - 2))
        hi_x = min(n, int(max(ax, bx) + half + 2))
        lo_y = max(0, int(min(ay, by) - half - 2))
        hi_y = min(n, int(max(ay, by) + half + 2))
        for y in range(lo_y, hi_y):
            for x in range(lo_x, hi_x):
                qx, qy = x + 0.5 - ax, y + 0.5 - ay
                t = 0.0 if seg2 == 0 else max(0.0, min(1.0, (qx * vx + qy * vy) / seg2))
                dx, dy = qx - t * vx, qy - t * vy
                if dx * dx + dy * dy <= half * half:
                    buf[y][x] = CLEAR if color is None else color


def downsample(buf, n, factor):
    out_n = n // factor
    out = [[CLEAR] * out_n for _ in range(out_n)]
    area = factor * factor
    for y in range(out_n):
        for x in range(out_n):
            r = g = b = a = 0
            for sy in range(y * factor, (y + 1) * factor):
                row = buf[sy]
                for sx in range(x * factor, (x + 1) * factor):
                    pr, pg, pb, pa = row[sx]
                    # premultiply, so transparent pixels drag no colour in
                    r += pr * pa
                    g += pg * pa
                    b += pb * pa
                    a += pa
            out[y][x] = CLEAR if a == 0 else (r // a, g // a, b // a, a // area)
    return out


def write_png(path, buf, n):
    raw = bytearray()
    for y in range(n):
        raw.append(0)  # filter type 0 (None)
        for x in range(n):
            raw.extend(buf[y][x])

    def chunk(tag, data):
        c = struct.pack(">I", len(data)) + tag + data
        return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", n, n, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    png += chunk(b"IEND", b"")

    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as fh:
        fh.write(png)
    print("  %-58s %dx%d" % (os.path.relpath(path, os.path.dirname(HERE)), n, n))


def centred_box(n, scale):
    size = n * scale
    off = (n - size) / 2.0
    return (off, off, size)


def make_status_icon(size):
    """White shield, pulse punched through to transparent. The system tints it."""
    n = size * SS
    buf = new_canvas(n)
    box = centred_box(n, 0.94)
    fill_shield(buf, n, box, WHITE)
    # Simplified trace and a heavier stroke: this is always shown at 24dp.
    stroke_polyline(buf, n, box, PULSE_SIMPLE, 0.115, None)
    return downsample(buf, n, SS)


def make_launcher_icon(size):
    """Legacy icon: dark rounded square, green shield, pulse cut back to the square."""
    n = size * SS
    buf = new_canvas(n)
    fill_round_rect(buf, n, 0.045 * n, 0.21 * n, BG)
    box = centred_box(n, 0.66)
    fill_shield(buf, n, box, FG)
    stroke_polyline(buf, n, box, PULSE, 0.095, BG)
    return downsample(buf, n, SS)


def make_adaptive_foreground(size):
    """
    Adaptive-icon foreground: 108dp canvas, content inside the 66dp safe zone.

    Launchers mask this to a circle/squircle and may parallax it, so the shield
    is scaled to ~0.55 of the canvas - comfortably inside the safe zone even
    though a shield is taller than it is wide.
    """
    n = size * SS
    buf = new_canvas(n)
    box = centred_box(n, 0.55)
    fill_shield(buf, n, box, FG)
    stroke_polyline(buf, n, box, PULSE, 0.095, None)
    return downsample(buf, n, SS)


def make_hero(size=512):
    """Large square mark for the README header and any store-style listing."""
    return make_launcher_icon(size)


def main():
    print("README hero:")
    write_png(os.path.join(os.path.dirname(HERE), "docs", "logo.png"),
              make_hero(512), 512)

    print("notification icon (white silhouette, 24dp baseline):")
    for bucket, size in (("mdpi", 24), ("hdpi", 36), ("xhdpi", 48), ("xxhdpi", 72)):
        write_png(os.path.join(RES, "drawable-" + bucket, "ic_stat_alive.png"),
                  make_status_icon(size), size)

    print("legacy launcher icon (48dp baseline):")
    for bucket, size in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96),
                         ("xxhdpi", 144), ("xxxhdpi", 192)):
        write_png(os.path.join(RES, "mipmap-" + bucket, "ic_launcher.png"),
                  make_launcher_icon(size), size)

    print("adaptive icon foreground (108dp baseline, API 26+):")
    for bucket, size in (("mdpi", 108), ("hdpi", 162), ("xhdpi", 216),
                         ("xxhdpi", 324), ("xxxhdpi", 432)):
        write_png(os.path.join(RES, "mipmap-" + bucket, "ic_launcher_foreground.png"),
                  make_adaptive_foreground(size), size)


if __name__ == "__main__":
    main()
