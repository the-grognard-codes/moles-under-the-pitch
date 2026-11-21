"""Check the two material ramps and the native mounting points on the Gatehouse."""
import json
import unittest

from PIL import Image, ImageChops, ImageColor, ImageDraw

import build


HERE = build.HERE
SEATS = HERE.parent
# Native rectangles with exclusive right and bottom edges. These openings and
# unlit bowls must remain clear, including when no supporters are present.
OPENINGS = (
    (185, 0, 244, 104), (546, 0, 606, 104),     # north stairs and arches
    (181, 1068, 243, 1168), (546, 1068, 608, 1168),  # south stairs
    (344, 1104, 442, 1188),                    # open passage at the south gate
    (281, 88, 306, 125), (486, 88, 510, 125),   # north braziers
    (288, 1025, 316, 1067), (474, 1025, 500, 1067),
)


def read(path):
    return json.loads(path.read_text(encoding="utf-8"))


def frame_bounds(frame):
    x, y = frame["x"], frame["y"]
    return x, y, x + frame["width"], y + frame["height"]


class RegaliaTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.atlas = Image.open(HERE / "atlas.png")
        cls.materials = Image.open(HERE / "materials.png")
        cls.seating = Image.open(SEATS / "empty-seating.png")
        with Image.open(SEATS / "empty-gatehouse.webp") as shell:
            cls.architecture = shell.getchannel("A")
        for image in (cls.atlas, cls.materials, cls.seating, cls.architecture):
            cls.addClassCleanup(image.close)
        cls.sheet = read(HERE / "atlas.json")
        cls.layout = read(HERE / "layout.json")
        cls.bays = {b["id"]: b["stand"] for b in read(SEATS / "empty-seating.json")["bays"]}
        ramps = read(HERE.parents[3] / "team-colors/v1/delivery/manifest.json")["ramps"]
        cls.ramps = {r["id"]: [ImageColor.getrgb(c) for c in r["colors"]] for r in ramps}

    def test_export_preserves_every_authored_material_and_fixed_pixel(self):
        size = self.sheet["width"], self.sheet["height"]
        self.assertEqual(("RGBA", size), (self.atlas.mode, self.atlas.size))
        self.assertEqual(("L", size), (self.materials.mode, self.materials.size))
        self.assertEqual(set(build.SPRITES), set(self.sheet["sprites"]))
        covered = set()
        hardware = [ImageColor.getrgb(c) for c in build.FITTINGS]
        for name, frame in self.sheet["sprites"].items():
            with self.subTest(sprite=name):
                x, y, right, bottom = frame_bounds(frame)
                self.assertEqual(build.SPRITES[name], (x, y, frame["width"], frame["height"]))
                self.assertTrue(0 <= x < right <= size[0] and 0 <= y < bottom <= size[1])
                source = build.artwork(name, (right - x, bottom - y))
                palette = [(0, 0, 0)] + self.ramps["red" if name.startswith("orc-") else "navy"] + self.ramps["gold"] + hardware
                used = set()
                for dx in range(source.width):
                    for dy in range(source.height):
                        pos = x + dx, y + dy
                        self.assertNotIn(pos, covered)
                        covered.add(pos)
                        index = source.getpixel((dx, dy))
                        expected_mask = index if 1 <= index <= 32 else 0
                        self.assertEqual(expected_mask, self.materials.getpixel(pos))
                        self.assertEqual((*palette[index], 255 if index else 0), self.atlas.getpixel(pos))
                        used.add(index)
                self.assertTrue(used & set(range(1, 17)) and used & set(range(17, 33)))
                self.assertTrue(any(index > 32 for index in used))
        for x in range(size[0]):
            for y in range(size[1]):
                if (x, y) not in covered:
                    self.assertEqual((0, 0, 0, 0), self.atlas.getpixel((x, y)))
                    self.assertEqual(0, self.materials.getpixel((x, y)))

    def test_each_attachment_meets_the_static_masonry(self):
        for p in self.layout["placements"]:
            with self.subTest(piece=p["id"]):
                name = p["sprite"]
                f = self.sheet["sprites"][name]
                width = f["width"]
                # Side poles anchor on their wall end. Each swag needs two fixings.
                left, right = range(0, 6), range(width - 6, width)
                mounts = [right] if name.endswith("-left") else [left]
                if name.endswith("-swag") or name.endswith("-standard"):
                    mounts = [left, right]
                for columns in mounts:
                    contact = any(
                        self.atlas.getpixel((f["x"] + x, f["y"] + y))[3] == 255
                        and self.materials.getpixel((f["x"] + x, f["y"] + y)) == 0
                        and self.architecture.getpixel((p["x"] + x, p["y"] + y)) == 255
                        for x in columns for y in range(1, 7)
                    )
                    self.assertTrue(contact, "floating fixture")

    def test_silhouettes_have_no_stray_pixels_and_rods_stay_fixed(self):
        for name, frame in self.sheet["sprites"].items():
            with self.subTest(sprite=name):
                box = frame_bounds(frame)
                alpha = self.atlas.crop(box).getchannel("A")
                mask = self.materials.crop(box)
                first = alpha.tobytes().index(255)
                seed = first % alpha.width, first // alpha.width
                ImageDraw.floodfill(alpha, seed, 0)
                self.assertIsNone(alpha.getbbox(), "detached pixel outside the fixture")
                if name.endswith("swag"):
                    bolts = [(3, 2), (38, 2)]
                else:
                    if name.endswith("standard"):
                        left, right, y = 2, 23, 3
                    elif name.endswith("shield"):
                        left, right, y = 1, 13, 2
                    elif name.endswith("right"):
                        left, right, y = 3, 24, 5
                    else:
                        left, right, y = 2, 23, 5
                    bolts = [(x, y) for x in range(left, right + 1)]
                for x, y in bolts:
                    self.assertEqual(0, mask.getpixel((x, y)), "team colour on a metal fitting")
                    self.assertEqual(255, self.atlas.getpixel((box[0] + x, box[1] + y))[3])

    def test_crests_are_distinct_inside_the_same_cloth_and_hardware(self):
        for suffix, design in (("standard", (7, 11, 19, 29)), ("pennant-right", (10, 16, 20, 34)),
                               ("pennant-left", (7, 16, 17, 34))):
            with self.subTest(suffix=suffix):
                human, orc = (self.materials.crop(frame_bounds(self.sheet["sprites"][f"{team}-{suffix}"]))
                              for team in ("human", "orc"))
                difference = ImageChops.difference(human, orc)
                self.assertIsNotNone(difference.crop(design).getbbox(), "both teams have the same crest")
                difference.paste(0, design)
                self.assertIsNone(difference.getbbox(), "changing the crest altered the surrounding cloth")

    def test_placed_silhouettes_clear_seating_pitch_passages_and_braziers(self):
        canvas = self.layout["canvas"]
        self.assertEqual((canvas["width"], canvas["height"]), self.seating.size)
        ids, painted = set(), set()
        passages = list(OPENINGS)
        for x in (21, 703):
            passages.extend((x, top, x + 65, bottom) for top, bottom in ((379, 435), (530, 589), (663, 719)))
        for x in (102, 662):
            passages.extend((x, top, x + 27, bottom) for top, bottom in ((392, 425), (533, 566), (675, 705)))
        for p in self.layout["placements"]:
            with self.subTest(piece=p["id"]):
                self.assertNotIn(p["id"], ids)
                ids.add(p["id"])
                owners = {"away" if self.bays[bay] == "east" else "home" for bay in p["bayIds"]}
                self.assertEqual({p["owner"]}, owners)
                f = self.sheet["sprites"][p["sprite"]]
                alpha = self.atlas.crop(frame_bounds(f)).getchannel("A")
                for x in range(alpha.width):
                    for y in range(alpha.height):
                        if not alpha.getpixel((x, y)):
                            continue
                        px, py = p["x"] + x, p["y"] + y
                        self.assertTrue(0 <= px < canvas["width"] and 0 <= py < canvas["height"])
                        self.assertNotIn((px, py), painted)
                        painted.add((px, py))
                        self.assertEqual(0, self.seating.getpixel((px, py)), "seating bay covered")
                        self.assertFalse(156 <= px < 636 and 169 <= py < 1001, "playable pitch covered")
                        self.assertFalse(any(l <= px < r and t <= py < b for l, t, r, b in passages))
        self.assertEqual({"home", "away"}, {p["owner"] for p in self.layout["placements"]})


if __name__ == "__main__":
    unittest.main()
