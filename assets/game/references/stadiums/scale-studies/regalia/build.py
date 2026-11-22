"""Draw Gatehouse regalia at native scale and export its cloth material plane."""
import json
from pathlib import Path

from PIL import Image, ImageColor, ImageDraw, ImageOps


HERE = Path(__file__).resolve().parent
SIZE = (172, 74)
SPRITES = {
    "human-standard": (0, 0, 26, 42),
    "orc-standard": (28, 0, 26, 42),
    "human-pennant-right": (56, 0, 27, 48),
    "human-pennant-left": (85, 0, 27, 48),
    "orc-pennant-right": (114, 0, 27, 48),
    "orc-pennant-left": (143, 0, 27, 48),
    "human-swag": (0, 50, 42, 13),
    "orc-swag": (44, 50, 42, 13),
    "human-shield": (88, 50, 15, 22),
}

# Only cloth uses the team ramps. The silhouette shadow, timber, bronze, and
# rivets are literal colors matched to the Gatehouse's warm masonry and trim.
SHADOW, IRON, WOOD, BRONZE_DARK, BRONZE, BRASS, GLINT = range(33, 40)
FITTINGS = ("#2a201d", "#41302a", "#69472e", "#825023", "#ab7333", "#d69d53", "#f7cc82")


def primary(shade):
    return 1 + shade


def secondary(shade):
    return 17 + shade


def pin(draw, x, y):
    draw.rectangle((x - 1, y - 1, x + 1, y + 1), fill=BRONZE_DARK)
    draw.point((x, y), fill=BRASS)
    draw.point((x - 1, y - 1), fill=GLINT)


def rod(draw, left, right, y):
    draw.line((left, y + 2, right, y + 2), fill=SHADOW)
    draw.line((left, y + 1, right, y + 1), fill=WOOD)
    draw.line((left, y, right, y), fill=BRONZE)
    draw.line((left + 2, y - 1, right - 1, y - 1), fill=BRASS)
    draw.line((left + 3, y - 1, left + 8, y - 1), fill=GLINT)
    for x in (left, right):
        draw.ellipse((x - 2, y - 2, x + 2, y + 2), fill=BRONZE_DARK)
        draw.line((x - 1, y, x + 1, y), fill=BRONZE)
        draw.line((x - 1, y - 1, x, y - 1), fill=BRASS)
        draw.point((x - 1, y - 1), fill=GLINT)
        draw.point((x + 1, y + 1), fill=WOOD)


def gather(draw, x, y):
    draw.line((x - 1, y, x - 1, y + 3), fill=secondary(4))
    draw.line((x + 1, y + 2, x + 1, y + 4), fill=primary(3))
    draw.line((x, y, x, y + 3), fill=secondary(10))
    draw.point((x, y + 1), fill=secondary(14))


def cloth_body(tile, outer, trim, field, seam_left, seam_right):
    draw = ImageDraw.Draw(tile)
    draw.polygon([(x + 1, y + 1) for x, y in outer], fill=SHADOW)
    draw.polygon(outer, fill=secondary(2))
    draw.polygon(trim, fill=secondary(10))
    # Clip the crease bands to the field so the gold edging remains continuous.
    area = Image.new("L", tile.size)
    ImageDraw.Draw(area).polygon(field, fill=255)
    weave = Image.new("L", tile.size, primary(8))
    fold = ImageDraw.Draw(weave)
    left, top, right, bottom = area.getbbox()
    middle = (left + right) // 2
    fold.polygon([(left, top), (right, top), (right, top + 2), (middle + 2, top + 4),
                  (middle - 2, top + 3), (left, top + 5)], fill=primary(5))
    fold.polygon([(left, bottom - 8), (middle, bottom - 5), (right, bottom - 12),
                  (right, bottom), (left, bottom)], fill=primary(6))
    fold.polygon(seam_left, fill=primary(9))
    fold.polygon(seam_right, fill=primary(4))
    fold.line(seam_right[:4], fill=primary(7))
    # Two brighter creases unfurl from the gathered header and fade toward the point.
    fold.line([(left + 1, top + 4), (left + 2, top + 9), (left + 2, top + 14)], fill=primary(12))
    fold.line([(left + 2, top + 14), (left + 3, top + 20), (left + 4, bottom - 4)], fill=primary(10))
    fold.line([(middle + 1, top + 2), (middle + 3, top + 6), (middle + 4, top + 10)], fill=primary(6))
    fold.line([(middle - 2, bottom - 10), (middle - 1, bottom - 6), (middle + 1, bottom - 4)], fill=primary(9))
    tile.paste(weave, (0, 0), area)
    # A narrow stitch highlight follows the light-facing hem.
    draw.line(trim[:3], fill=secondary(13))
    draw.line(trim[2:4], fill=secondary(11))
    draw.line((trim[1][0], trim[1][1] - 4, trim[1][0], trim[1][1] - 2), fill=secondary(15))
    draw.line((trim[-1][0], trim[-1][1] + 3, trim[-1][0], trim[-1][1] + 7), fill=secondary(12))


def lion(draw, x, y):
    """Rampant lion facing left, with split paws and an upturned tail."""
    def line(points, shade, width=1):
        draw.line([(x + px, y + py) for px, py in points], fill=secondary(shade), width=width)

    def shape(points, shade):
        draw.polygon([(x + px, y + py) for px, py in points], fill=secondary(shade))

    line([(8, 12), (11, 10), (12, 7), (12, 4), (11, 2), (10, 2), (10, 4)], 10, 2)
    shape([(4, 2), (6, 1), (6, 0), (8, 3), (8, 5), (7, 6), (8, 8), (10, 10),
           (9, 13), (6, 13), (5, 11), (6, 9), (4, 7), (4, 5), (2, 5), (1, 4), (4, 3)], 11)
    line([(6, 8), (4, 8), (2, 7), (0, 7)], 12, 2)
    line([(6, 9), (4, 10), (2, 10), (1, 11)], 9, 2)
    line([(6, 11), (4, 13), (3, 15), (1, 15)], 11, 2)
    line([(8, 12), (10, 14), (9, 16), (7, 16)], 9, 2)
    line([(5, 2), (6, 2), (7, 3)], 14)
    line([(4, 6), (5, 7), (6, 8)], 13)
    line([(8, 9), (8, 11), (7, 12)], 14)
    draw.point((x + 4, y + 4), fill=secondary(3))


def crescent(draw, x, y):
    """Orc tusks surrounding a crescent with a downward central fang."""
    outer = [(1, 1), (3, 4), (4, 8), (4, 10), (6, 12), (8, 12), (10, 10),
             (10, 7), (13, 1), (13, 8), (12, 12), (10, 14), (8, 15), (7, 17),
             (6, 15), (4, 14), (2, 12), (1, 9)]
    draw.polygon([(x + px, y + py) for px, py in outer], fill=secondary(7))
    draw.line([(x + px, y + py) for px, py in
               [(1, 2), (2, 7), (3, 11), (5, 13), (8, 13), (11, 11), (12, 7), (13, 2)]],
              fill=secondary(12), width=2)
    draw.line((x + 2, y + 4, x + 2, y + 7), fill=secondary(14))
    draw.line((x + 12, y + 4, x + 12, y + 7), fill=secondary(14))
    draw.point((x + 7, y + 15), fill=secondary(11))


def place_emblem(tile, emblem, origin, size):
    glyph = Image.new("L", (14, 18))
    emblem(ImageDraw.Draw(glyph), 0, 0)
    glyph = glyph.resize(size, Image.Resampling.NEAREST)
    tile.paste(glyph, origin, glyph.point(lambda value: 255 if value else 0))


def standard(size, emblem):
    tile = Image.new("L", size)
    cloth_body(tile,
               [(3, 6), (3, 28), (6, 32), (13, 40), (20, 33), (22, 28), (22, 6)],
               [(4, 7), (4, 28), (7, 32), (13, 38), (19, 32), (21, 28), (21, 7)],
               [(6, 7), (6, 27), (9, 32), (13, 36), (17, 32), (19, 27), (19, 7)],
               [(7, 7), (7, 17), (8, 26), (10, 34), (8, 34), (6, 27), (6, 7)],
               [(15, 7), (15, 16), (18, 26), (15, 37), (21, 36), (21, 7)])
    draw = ImageDraw.Draw(tile)
    place_emblem(tile, emblem, (7, 11), (12, 18))
    draw.line((4, 6, 21, 6), fill=primary(4))
    rod(draw, 2, 23, 3)
    gather(draw, 6, 4)
    gather(draw, 19, 4)
    return tile


def pennant(size, face, emblem):
    tile = Image.new("L", size)
    cloth_body(tile,
               [(6, 7), (6, 31), (9, 37), (15, 46), (21, 38), (23, 32), (23, 7)],
               [(7, 8), (7, 31), (10, 37), (15, 44), (20, 37), (22, 31), (22, 8)],
               [(9, 8), (9, 31), (11, 35), (15, 41), (19, 35), (20, 31), (20, 8)],
               [(9, 8), (10, 18), (10, 29), (13, 40), (11, 39), (8, 31), (8, 8)],
               [(16, 8), (16, 20), (18, 28), (16, 42), (22, 38), (22, 8)])
    draw = ImageDraw.Draw(tile)
    draw.polygon([(1, 3), (4, 2), (6, 4), (6, 8), (4, 12), (1, 12)], fill=SHADOW)
    draw.polygon([(1, 3), (4, 3), (5, 4), (5, 8), (4, 10), (1, 10)], fill=IRON)
    draw.line((1, 4, 1, 9), fill=WOOD)
    draw.line((2, 3, 4, 3), fill=BRONZE)
    draw.point((3, 3), fill=BRASS)
    draw.point((3, 9), fill=BRASS)
    rod(draw, 3, 24, 5)
    gather(draw, 8, 6)
    gather(draw, 21, 6)
    if face == "left":
        tile = ImageOps.mirror(tile)
    # The bracket changes sides while the heraldry remains readable.
    place_emblem(tile, emblem, (10 if face == "right" else 7, 16), (10, 18))
    return tile


def swag(size):
    tile = Image.new("L", size)
    draw = ImageDraw.Draw(tile)
    for x in range(3, 39):
        sag = (x - 3) * (38 - x)
        top = 2 + sag // 67
        bottom = 5 + sag // 45
        draw.line((x, top + 1, x, bottom + 1), fill=SHADOW)
        draw.line((x, top, x, bottom), fill=primary(7 if x < 25 else 6))
        draw.point((x, top), fill=primary(3))
        ridge = top + (1 if x < 19 or x > 34 else 2)
        draw.point((x, ridge), fill=primary(12 if x < 18 else 10))
        draw.point((x, bottom - 1), fill=primary(5 if x < 26 else 4))
        trim = 13 if x in (8, 9, 25, 26) else (9 if x < 30 else 7)
        draw.point((x, bottom), fill=secondary(trim))
    draw.line([(5, 3), (7, 4), (10, 5)], fill=primary(11))
    draw.line([(32, 5), (35, 4), (37, 3)], fill=primary(8))
    pin(draw, 3, 2)
    pin(draw, 38, 2)
    return tile


def shield(size):
    tile = Image.new("L", size)
    draw = ImageDraw.Draw(tile)
    draw.polygon([(2, 4), (13, 4), (13, 15), (8, 21), (2, 16)], fill=SHADOW)
    draw.polygon([(1, 3), (13, 3), (13, 14), (7, 20), (1, 14)], fill=secondary(9))
    draw.polygon([(2, 4), (12, 4), (12, 13), (7, 18), (2, 13)], fill=primary(7))
    draw.line((3, 4, 3, 13), fill=primary(11))
    draw.line((1, 4, 1, 13), fill=secondary(13))
    place_emblem(tile, lion, (4, 5), (8, 11))
    rod(draw, 1, 13, 2)
    return tile


def artwork(sprite, size):
    emblem = lion if sprite.startswith("human-") else crescent
    if "-pennant-" in sprite:
        return pennant(size, sprite.rsplit("-", 1)[1], emblem)
    if sprite.endswith("-swag"):
        return swag(size)
    if sprite.endswith("-shield"):
        return shield(size)
    return standard(size, emblem)


def build():
    ramp_path = HERE.parents[3] / "team-colors/v1/delivery/manifest.json"
    ramps = {ramp["id"]: ramp["colors"] for ramp in json.loads(ramp_path.read_text(encoding="utf-8"))["ramps"]}
    base_primary = {"human": "navy", "orc": "red"}
    atlas = Image.new("RGBA", SIZE)
    materials = Image.new("L", SIZE)
    for sprite, (x, y, w, h) in SPRITES.items():
        raw = artwork(sprite, (w, h))
        profile = base_primary[sprite.split("-", 1)[0]]
        rgb = ("#000000", *ramps[profile], *ramps["gold"], *FITTINGS)
        colored = Image.frombytes("P", raw.size, raw.tobytes())
        colored.putpalette([q for color in rgb for q in ImageColor.getrgb(color)])
        colored.info["transparency"] = 0
        atlas.paste(colored.convert("RGBA"), (x, y))
        materials.paste(raw.point(lambda index: index if 1 <= index <= 32 else 0), (x, y))

    atlas.save(HERE / "atlas.png", optimize=True)
    materials.save(HERE / "materials.png", optimize=True)
    manifest = {"width": SIZE[0], "height": SIZE[1], "sprites": {
        sprite: dict(zip(("x", "y", "width", "height"), bounds)) for sprite, bounds in SPRITES.items()
    }}
    (HERE / "atlas.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    build()
