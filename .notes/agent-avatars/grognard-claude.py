"""Pixel-art avatar for grognard-claude[bot]: a bespectacled reviewer mole surfacing through the pitch."""
import struct
import sys
import zlib

N = 32
SCALE = 16

BG = (236, 170, 128)
BG_DARK = (226, 154, 112)
GRASS = (92, 160, 78)
GRASS_DARK = (64, 126, 58)
SOIL = (110, 70, 38)
SOIL_DARK = (82, 50, 26)
HOLE = (44, 26, 16)
OUTLINE = (40, 30, 27)
FUR = (92, 76, 70)
FUR_LIGHT = (122, 104, 96)
SNOUT = (236, 160, 160)
NOSE = (186, 76, 94)
GOLD = (246, 198, 70)
LENS = (220, 240, 246)
PUPIL = (24, 18, 18)
PAW = (232, 178, 158)
CLAW = (252, 240, 228)
BADGE = (255, 246, 232)
CHECK = (64, 140, 70)

grid = [[BG for _ in range(N)] for _ in range(N)]


def put(x, y, c):
    if 0 <= x < N and 0 <= y < N:
        grid[y][x] = c


def ellipse(cx, cy, rx, ry, c, ymax=None):
    for y in range(N):
        for x in range(N):
            if ymax is not None and y > ymax:
                continue
            dx = (x + 0.5 - cx) / rx
            dy = (y + 0.5 - cy) / ry
            if dx * dx + dy * dy <= 1.0:
                put(x, y, c)


# Background: soft diagonal stripes, like a mown pitch seen through a window.
for y in range(N):
    for x in range(N):
        if ((x + y) // 4) % 2 == 0:
            put(x, y, BG_DARK)

# Ground: grass band over soil.
for y in range(22, N):
    for x in range(N):
        put(x, y, GRASS if y < 24 else SOIL)
for x in range(N):
    if x % 3 == 0:
        put(x, 21, GRASS)
    if x % 3 == 1:
        put(x, 23, GRASS_DARK)
for (x, y) in [(3, 27), (8, 29), (14, 28), (20, 30), (26, 27), (29, 30), (5, 31), (17, 26), (23, 29), (11, 31)]:
    put(x, y, SOIL_DARK)

# Hole the mole is coming out of.
ellipse(16, 24.2, 11.5, 2.6, HOLE)

# Mole: outline pass first, then fur.
ellipse(16, 21.5, 10.0, 8.6, OUTLINE, ymax=24)
ellipse(16, 13.0, 9.6, 8.6, OUTLINE)
ellipse(16, 21.5, 9.0, 7.6, FUR, ymax=24)
ellipse(16, 13.0, 8.6, 7.6, FUR)
ellipse(12.5, 8.8, 3.2, 1.8, FUR_LIGHT)

# Snout and nose.
ellipse(16, 18.0, 3.4, 2.6, OUTLINE)
ellipse(16, 18.0, 2.7, 2.0, SNOUT)
ellipse(16, 16.9, 1.7, 1.1, NOSE)

# Reading glasses.
for cx in (12.0, 20.0):
    ellipse(cx, 12.5, 3.1, 3.1, GOLD)
    ellipse(cx, 12.5, 2.0, 2.0, LENS)
for x in (15, 16):
    put(x, 12, GOLD)
put(13, 12, PUPIL)
put(13, 13, PUPIL)
put(21, 12, PUPIL)
put(21, 13, PUPIL)
put(11, 11, (255, 255, 255))
put(19, 11, (255, 255, 255))

# Big digging paws gripping the rim of the hole.
for cx in (8.5, 23.5):
    ellipse(cx, 23.0, 3.0, 1.9, OUTLINE)
    ellipse(cx, 23.0, 2.3, 1.3, PAW)
for x in (6, 8, 10):
    put(x, 24, CLAW)
for x in (21, 23, 25):
    put(x, 24, CLAW)

BADGE_SPRITE = [
    "..OOOOO..",
    ".OBBBBBO.",
    "OBBBBBCBO",
    "OBBBBCCBO",
    "OBCBCCBBO",
    "OBCCCBBBO",
    "OBBCBBBBO",
    ".OBBBBBO.",
    "..OOOOO..",
]

MODE = sys.argv[2] if len(sys.argv) > 2 else "square"
ROUND = MODE in ("round", "clear")
CLEAR = MODE == "clear"
DISC = MODE == "disc"

if DISC:
    # Full-bleed disc: flat background, scene drawn whole; the ring and the badge
    # are composited at full resolution in write_disc_png.
    for y in range(N):
        for x in range(N):
            if grid[y][x] == BG_DARK:
                put(x, y, BG)
    tick = []
elif ROUND:
    # Clip the scene to a circle; the corners take the flat background so GitHub's
    # circular frame (with its badge colour set to BG) shows one round avatar.
    for y in range(N):
        for x in range(N):
            dx = x + 0.5 - 16
            dy = y + 0.5 - 16
            if dx * dx + dy * dy > 15.6 * 15.6 or grid[y][x] == BG_DARK:
                put(x, y, BG)
    # Review badge, pulled inside the circle.
    badge = BADGE_SPRITE
    colours = {"O": OUTLINE, "B": BADGE, "C": CHECK}
    for dy, line in enumerate(badge):
        for dx, ch in enumerate(line):
            if ch in colours:
                put(20 + dx, 3 + dy, colours[ch])
    tick = []
else:
    # Review badge: an approving tick, top right.
    ellipse(25.5, 6.0, 4.4, 4.4, OUTLINE)
    ellipse(25.5, 6.0, 3.6, 3.6, BADGE)
    tick = [(28, 4), (27, 5), (28, 5), (23, 6), (26, 6), (27, 6), (23, 7), (24, 7), (25, 7), (26, 7), (24, 8), (25, 8)]
for (x, y) in tick:
    put(x, y, CHECK)

def png_bytes(w, h, rgba_rows):
    def chunk(tag, data):
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    raw = bytearray()
    for row in rgba_rows:
        raw.append(0)
        raw.extend(row)
    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    png += chunk(b"IEND", b"")
    return png


RING = (198, 98, 58)
RING_LIGHT = (240, 150, 96)


def write_disc_png(path):
    """512px full-bleed disc: anti-aliased ring, pixel scene inside, badge as a sticker over the ring."""
    size = 512
    centre = size / 2
    cell = 13
    offset = (size - N * cell) // 2
    r_outer, r_ring, r_ring_light, r_inner = 255.5, 247.0, 229.0, 223.0
    bx, by, bcell = offset + 20 * cell, offset + 3 * cell, cell
    badge_colours = {"O": OUTLINE, "B": BADGE, "C": CHECK}

    def sample(px, py):
        bgx, bgy = int((px - bx) // bcell), int((py - by) // bcell)
        if 0 <= bgy < len(BADGE_SPRITE) and 0 <= bgx < len(BADGE_SPRITE[0]):
            ch = BADGE_SPRITE[bgy][bgx]
            if ch in badge_colours:
                return badge_colours[ch] + (255,)
        d = ((px - centre) ** 2 + (py - centre) ** 2) ** 0.5
        if d > r_outer:
            return (0, 0, 0, 0)
        if d > r_ring:
            return OUTLINE + (255,)
        if d > r_ring_light:
            return (RING_LIGHT if d < r_ring_light + 5 else RING) + (255,)
        if d > r_inner:
            return OUTLINE + (255,)
        # Outside the 32x32 scene, extend its edge pixels out to the ring.
        gx = min(max(int((px - offset) // cell), 0), N - 1)
        gy = min(max(int((py - offset) // cell), 0), N - 1)
        return grid[gy][gx] + (255,)

    subs = [(i + 0.5) / 4 for i in range(4)]
    rows = []
    for y in range(size):
        row = bytearray()
        for x in range(size):
            acc = [0, 0, 0, 0]
            for sy in subs:
                for sx in subs:
                    r, g, b, a = sample(x + sx, y + sy)
                    acc[0] += r * a
                    acc[1] += g * a
                    acc[2] += b * a
                    acc[3] += a
            if acc[3]:
                row.extend((round(acc[0] / acc[3]), round(acc[1] / acc[3]), round(acc[2] / acc[3]), round(acc[3] / 16)))
            else:
                row.extend((0, 0, 0, 0))
        rows.append(row)
    with open(path, "wb") as f:
        f.write(png_bytes(size, size, rows))


def write_png(path, pixels, scale):
    # In clear mode the background is transparent, so GitHub's badge colour shows through.
    w = h = N * scale
    raw = bytearray()
    for y in range(h):
        raw.append(0)
        row = pixels[y // scale]
        for x in range(w):
            c = row[x // scale]
            raw.extend(c)
            raw.append(0 if CLEAR and c == BG else 255)

    def chunk(tag, data):
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    png += chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(png)


if DISC:
    write_disc_png(sys.argv[1])
else:
    write_png(sys.argv[1], grid, SCALE)
