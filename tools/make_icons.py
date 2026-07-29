#!/usr/bin/env python3
"""Generate AppAlive's PNG icons with no third-party dependencies.

Notification small icons must be PNG: VectorDrawable cannot be inflated inside
RemoteViews below API 24, so a vector would fail on part of our minSdk 21 range.
Rather than commit opaque binaries, we generate them here from a tiny
supersampled rasterizer (stdlib zlib/struct only).

    python tools/make_icons.py

Writes app/res/drawable-*/ic_stat_alive.png and app/res/mipmap-*/ic_launcher.png.
"""

import os
import struct
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(os.path.dirname(HERE), "app", "res")

SS = 4  # supersampling factor, for antialiasing

# An ECG/heartbeat trace, in unit coordinates (0..1), y down.
PULSE = [
    (0.08, 0.54), (0.30, 0.54), (0.39, 0.26), (0.50, 0.78),
    (0.60, 0.42), (0.68, 0.54), (0.92, 0.54),
]

BG = (0x10, 0x33, 0x3F, 0xFF)      # dark slate
FG = (0x34, 0xD3, 0x99, 0xFF)      # "alive" green
WHITE = (0xFF, 0xFF, 0xFF, 0xFF)


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
        return (0, 0, 0, 0)
    out = [int(round((src[i] * sa + dst[i] * da * (1 - sa)) / oa)) for i in range(3)]
    return (out[0], out[1], out[2], int(round(oa * 255)))


def new_canvas(n):
    return [[(0, 0, 0, 0)] * n for _ in range(n)]


def fill_round_rect(buf, n, inset, radius, color):
    """Filled rounded square covering [inset, n-inset) with corner `radius`."""
    x0 = y0 = inset
    x1 = y1 = n - inset
    for y in range(n):
        for x in range(n):
            # distance-to-rounded-rect test
            cx = min(max(x + 0.5, x0 + radius), x1 - radius)
            cy = min(max(y + 0.5, y0 + radius), y1 - radius)
            dx = (x + 0.5) - cx
            dy = (y + 0.5) - cy
            if dx * dx + dy * dy <= radius * radius:
                buf[y][x] = blend(buf[y][x], color)


def stroke_polyline(buf, n, pts, width, color):
    """Round-capped, round-joined polyline via per-segment distance test."""
    half = width / 2.0
    px = [(x * n, y * n) for (x, y) in pts]
    for i in range(len(px) - 1):
        ax, ay = px[i]
        bx, by = px[i + 1]
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
                    buf[y][x] = color


def downsample(buf, n, factor):
    """Box-filter the supersampled canvas down to its final size."""
    out_n = n // factor
    out = [[(0, 0, 0, 0)] * out_n for _ in range(out_n)]
    area = factor * factor
    for y in range(out_n):
        for x in range(out_n):
            r = g = b = a = 0
            for sy in range(y * factor, (y + 1) * factor):
                row = buf[sy]
                for sx in range(x * factor, (x + 1) * factor):
                    pr, pg, pb, pa = row[sx]
                    # premultiply so transparent pixels don't drag colour in
                    r += pr * pa
                    g += pg * pa
                    b += pb * pa
                    a += pa
            if a == 0:
                out[y][x] = (0, 0, 0, 0)
            else:
                out[y][x] = (r // a, g // a, b // a, a // area)
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
    print("  %-56s %dx%d" % (os.path.relpath(path, os.path.dirname(HERE)), n, n))


def make_status_icon(size):
    """White pulse on transparent; the system tints status-bar icons itself."""
    n = size * SS
    buf = new_canvas(n)
    stroke_polyline(buf, n, PULSE, 0.10 * n, WHITE)
    return downsample(buf, n, SS)


def make_launcher_icon(size):
    n = size * SS
    buf = new_canvas(n)
    fill_round_rect(buf, n, 0.045 * n, 0.21 * n, BG)
    stroke_polyline(buf, n, PULSE, 0.085 * n, FG)
    return downsample(buf, n, SS)


def main():
    # Notification icon: 24dp baseline.
    print("notification icon (white on transparent):")
    for bucket, size in (("mdpi", 24), ("hdpi", 36), ("xhdpi", 48), ("xxhdpi", 72)):
        write_png(os.path.join(RES, "drawable-" + bucket, "ic_stat_alive.png"),
                  make_status_icon(size), size)

    # Launcher icon: 48dp baseline.
    print("launcher icon:")
    for bucket, size in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96),
                         ("xxhdpi", 144), ("xxxhdpi", 192)):
        write_png(os.path.join(RES, "mipmap-" + bucket, "ic_launcher.png"),
                  make_launcher_icon(size), size)


if __name__ == "__main__":
    main()
