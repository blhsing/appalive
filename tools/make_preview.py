#!/usr/bin/env python3
"""Render a contact sheet of the AppAlive icon set, at real pixel sizes.

Re-renders the marks from make_icons rather than reading the PNGs back, so
there is no PNG decoder to maintain.

    python tools/make_preview.py     ->  docs/logo-preview.png

Rows, top to bottom:
  1  legacy launcher icon      192 / 96 / 48 px
  2  adaptive icon, circle- and squircle-masked, as a launcher would show it
  3  notification glyph on a dark bar, 72 / 48 / 36 / 24 px (tinted white)
"""

import os
import struct
import zlib

import make_icons as M

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(os.path.dirname(HERE), "docs", "logo-preview.png")

W, H = 880, 470
LIGHT = (0xF3, 0xF4, 0xF6, 0xFF)
DARK = (0x17, 0x1A, 0x1E, 0xFF)
RULE = (0xD6, 0xDA, 0xDE, 0xFF)


def new_rect(w, h, color):
    return [[color] * w for _ in range(h)]


def paste(dst, src, n, x0, y0):
    for y in range(n):
        ty = y0 + y
        if ty < 0 or ty >= len(dst):
            continue
        for x in range(n):
            tx = x0 + x
            if 0 <= tx < len(dst[0]):
                dst[ty][tx] = M.blend(dst[ty][tx], src[y][x])


def paste_masked(dst, src, n, x0, y0, mask):
    """mask(u, v) -> bool, u/v in 0..1 across the icon."""
    for y in range(n):
        ty = y0 + y
        if ty < 0 or ty >= len(dst):
            continue
        for x in range(n):
            tx = x0 + x
            if not (0 <= tx < len(dst[0])):
                continue
            if mask((x + 0.5) / n, (y + 0.5) / n):
                dst[ty][tx] = M.blend(dst[ty][tx], src[y][x])


def circle_mask(u, v):
    du, dv = u - 0.5, v - 0.5
    return du * du + dv * dv <= 0.25


def squircle_mask(u, v):
    du, dv = abs(u - 0.5) / 0.5, abs(v - 0.5) / 0.5
    return (du ** 3.2 + dv ** 3.2) <= 1.0


def adaptive_composite(size):
    """Background colour + foreground, exactly as the platform composes it."""
    buf = new_rect(size, size, M.BG)
    fg = M.make_adaptive_foreground(size)
    for y in range(size):
        for x in range(size):
            buf[y][x] = M.blend(buf[y][x], fg[y][x])
    return buf


def hline(dst, y, color):
    for x in range(40, W - 40):
        dst[y][x] = color


def write_png_rect(path, buf, w, h):
    raw = bytearray()
    for y in range(h):
        raw.append(0)
        for x in range(w):
            raw.extend(buf[y][x])

    def chunk(tag, data):
        c = struct.pack(">I", len(data)) + tag + data
        return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    png += chunk(b"IEND", b"")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as fh:
        fh.write(png)
    print("wrote %s (%dx%d)" % (os.path.relpath(path, os.path.dirname(HERE)), w, h))


def main():
    canvas = new_rect(W, H, LIGHT)

    # row 1 - legacy launcher icon at three real sizes
    y = 40
    paste(canvas, M.make_launcher_icon(192), 192, 60, y)
    paste(canvas, M.make_launcher_icon(96), 96, 300, y + 48)
    paste(canvas, M.make_launcher_icon(48), 48, 430, y + 72)

    # row 1 (right) - adaptive icon under the two common launcher masks
    paste_masked(canvas, adaptive_composite(160), 160, 540, y + 16, circle_mask)
    paste_masked(canvas, adaptive_composite(160), 160, 710, y + 16, squircle_mask)

    hline(canvas, 268, RULE)

    # row 2 - the mark on its own, oversized, to show the knockout
    big = M.make_launcher_icon(160)
    paste(canvas, big, 160, 60, 292)

    # row 3 - notification glyph on a dark bar, as the status bar renders it
    for yy in range(292, 292 + 160):
        for xx in range(260, W - 40):
            canvas[yy][xx] = DARK
    base = 292 + 44
    paste(canvas, M.make_status_icon(72), 72, 300, base - 12)
    paste(canvas, M.make_status_icon(48), 48, 420, base)
    paste(canvas, M.make_status_icon(36), 36, 510, base + 6)
    paste(canvas, M.make_status_icon(24), 24, 580, base + 12)
    # and again at 24px repeated, the way a status bar actually shows it
    for i in range(4):
        paste(canvas, M.make_status_icon(24), 24, 650 + i * 34, base + 12)

    write_png_rect(OUT, canvas, W, H)


if __name__ == "__main__":
    main()
