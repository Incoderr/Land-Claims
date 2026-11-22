"""Draws the Land Claims icon as 32x32 pixel art and writes it scaled up without smoothing.
Usage: python tools/make_icon.py src/main/resources/landclaims.png 8 icon.png 16
"""
import struct
import sys
import zlib

N = 32


def hexrgb(h):
    return ((h >> 16) & 255, (h >> 8) & 255, h & 255, 255)


def blend(base, over, a):
    return tuple(round(base[i] * (1 - a) + over[i] * a) for i in range(3)) + (255,)


def noise(x, y):
    v = (x * 374761393 + y * 668265263) & 0xFFFFFFFF
    v = ((v ^ (v >> 13)) * 1274126177) & 0xFFFFFFFF
    return (v >> 16) & 0xFF


img = [[(0, 0, 0, 0)] * N for _ in range(N)]

# Grass with a little texture.
grass = [hexrgb(0x5DA03A), hexrgb(0x6AB043), hexrgb(0x528F33), hexrgb(0x63A83E)]
for y in range(N):
    for x in range(N):
        img[y][x] = grass[noise(x, y) % 4]

# A lake in the top-left corner and a sand rim.
water, water_dark, sand = hexrgb(0x3F76E4), hexrgb(0x335FC2), hexrgb(0xDBCB8A)
for y in range(N):
    for x in range(N):
        d = ((x - 3) ** 2) / 49 + ((y - 4) ** 2) / 30
        if d < 1:
            img[y][x] = water_dark if d < 0.45 else water
        elif d < 1.35:
            img[y][x] = sand

# A few trees: dark canopy with a lighter top.
leaf, leaf_light, leaf_dark = hexrgb(0x2F6B22), hexrgb(0x3E8A2C), hexrgb(0x24531A)
for tx, ty in [(26, 4), (28, 27), (4, 26), (27, 15), (12, 28)]:
    for dy in range(-2, 3):
        for dx in range(-2, 3):
            if abs(dx) + abs(dy) <= 3 and 0 <= tx + dx < N and 0 <= ty + dy < N:
                c = leaf_light if dy < 0 and dx < 1 else leaf_dark if dy > 0 else leaf
                img[ty + dy][tx + dx] = c

# Chunk grid every 8 pixels.
for y in range(N):
    for x in range(N):
        if x % 8 == 0 or y % 8 == 0:
            img[y][x] = blend(img[y][x], (0, 0, 0), 0.26)

# The claim: 2x2 chunks, gold tint with a solid border like on the claim map.
gold, gold_dark = hexrgb(0xFFC94D), hexrgb(0xB8862B)
x0, y0, x1, y1 = 8, 9, 24, 25
for y in range(y0, y1):
    for x in range(x0, x1):
        img[y][x] = blend(img[y][x], gold, 0.30)
for x in range(x0, x1):
    img[y0][x] = gold
    img[y1 - 1][x] = gold_dark
for y in range(y0, y1):
    img[y][x0] = gold
    img[y][x1 - 1] = gold_dark

# Flag: pole with shadow, red cloth with a white emblem.
pole, pole_dark, shadow = hexrgb(0x8A5A2B), hexrgb(0x5E3C1B), (0, 0, 0)
red, red_dark, white = hexrgb(0xE05050), hexrgb(0xA83434), hexrgb(0xF4F4F4)
for y in range(5, 22):
    img[y][15] = pole
    img[y][16] = pole_dark
for x in range(14, 19):
    img[22][x] = blend(img[22][x], shadow, 0.45)
img[21][14] = blend(img[21][14], shadow, 0.3)
notch = {(24, 7), (24, 8), (24, 9), (23, 8)}
for y in range(5, 12):
    for x in range(17, 25):
        if (x, y) in notch:
            continue
        img[y][x] = red_dark if y == 11 or (x, y) in {(23, 7), (23, 9), (24, 6), (24, 10)} else red
for x, y in [(20, 7), (19, 8), (20, 8), (21, 8), (20, 9)]:
    img[y][x] = white
img[4][15] = hexrgb(0xFFC94D)
img[4][16] = hexrgb(0xB8862B)

# Dark frame.
frame = hexrgb(0x1A1C20)
for i in range(N):
    img[0][i] = img[N - 1][i] = img[i][0] = img[i][N - 1] = frame


def write_png(path, scale):
    size = N * scale
    raw = bytearray()
    for y in range(size):
        raw.append(0)
        for x in range(size):
            raw.extend(img[y // scale][x // scale])

    def chunk(kind, data):
        c = struct.pack(">I", len(data)) + kind + data
        return c + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    png += chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(png)


for path, scale in zip(sys.argv[1::2], map(int, sys.argv[2::2])):
    write_png(path, scale)
    print("wrote", path, N * scale)
