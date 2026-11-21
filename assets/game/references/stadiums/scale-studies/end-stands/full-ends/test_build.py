from copy import deepcopy
import unittest

from PIL import Image

import build


def position(x, y):
    return {"x": x, "y": y}


def seat(plank, x, y, supporter, **attachments):
    return {"plankId": plank, "slot": position(x, y), "x": x, "y": y, "supporter": supporter,
            "pose": f"north-{supporter}", "neutral": supporter in build.proof.NEUTRAL, **attachments}


class FullEndStandTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.mask, cls.bays, cls.supports = build.proof.geometry()
        cls.landmarks = build.proof.read(build.HERE.parent / "pose-landmarks.json")
        with Image.open(build.HERE.parent / "supporters.png") as atlas:
            cls.tiles = build.pose_art(atlas)
            cls.palette = atlas.getpalette()

    def test_exports_preserve_native_palette_masks_and_protected_features(self):
        plan = build.proof.read(build.HERE / "layout.json")
        records = build.proof.read(build.HERE / "placements.json")
        rendered = build.render(plan, self.mask, self.bays, self.supports, self.tiles, self.landmarks)
        self.assertEqual(list(build.BAY_IDS), [record["bayId"] for record in records])
        self.assertEqual(records, [record for _, record in rendered])
        for record, (expected, _) in zip(records, rendered, strict=True):
            with self.subTest(bay=record["bayId"]):
                bay = self.bays[record["bayId"]]
                path = build.HERE / record["crowd"]
                build.proof.fit.check_insert(self.mask, bay, path)
                self.assertEqual(record["capacity"], len(record["slots"]))
                self.assertGreater(len(record["seats"]), 0)
                with Image.open(path) as crowd:
                    self.assertEqual("P", crowd.mode)
                    self.assertEqual(0, crowd.info["transparency"])
                    self.check_home_palette(crowd)
                    self.assertEqual(expected.tobytes(), crowd.tobytes())
                    self.check_protected_pixels(record, bay, crowd)

    def check_home_palette(self, crowd):
        output = crowd.getpalette()
        self.assertEqual(self.palette[:3], output[:3])
        self.assertEqual(self.palette[99:], output[99:])
        ramps = build.team_ramps()
        for start, material in ((1, "navy"), (17, "gold")):
            colors = ["#" + bytes(output[i:i + 3]).hex() for i in range(3 * start, 3 * (start + 16), 3)]
            self.assertEqual(ramps[material], colors)

    def check_protected_pixels(self, record, bay, crowd):
        union = set()
        for seat in record["seats"]:
            tile, _, alpha = self.tiles[seat["pose"]]
            marks = self.landmarks[seat["pose"]]
            if seat["neutral"]:
                self.assertTrue(set(tile.tobytes()).isdisjoint(range(1, 33)))
            hip_x, hip_y = marks["contact"]
            origin = seat["x"] - hip_x, seat["y"] - hip_y
            visible = set()
            for y in range(tile.height):
                for x in range(tile.width):
                    if alpha.getpixel((x, y)):
                        point = origin[0] + x, origin[1] + y
                        self.assertEqual(bay["id"], self.mask.getpixel((bay["x"] + point[0], bay["y"] + point[1])))
                        visible.add(point)
            union.update(visible)
            protected = build.proof.screened_region(visible, origin, marks["face"])
            protected.update(build.proof.screened_region(visible, origin, marks.get("gesture")))
            if "feet" in seat:
                baseline = max(y for _, y in visible)
                protected.update((x, y) for x, y in visible if y == baseline)
            for x, y in protected:
                self.assertEqual(tile.getpixel((x - origin[0], y - origin[1])), crowd.getpixel((x, y)))
        self.assertEqual(len(union), sum(index != 0 for index in crowd.tobytes()))

    def test_requires_an_empty_adjacent_upper_slot_for_standing(self):
        standing = seat(13, 52, 48, "standing-tambourine", feet=[position(52, 48), position(60, 48)])
        near = seat(12, 50, 25, "horn")
        tied = seat(12, 54, 25, "pie")
        far = seat(12, 89, 25, "clap")
        record = {"bayId": 1, "slots": [standing, near, tied, far], "seats": [standing, far]}
        build.validate_clearance(record)
        record["seats"].append(tied)
        with self.assertRaisesRegex(ValueError, "vacant place directly above"):
            build.validate_clearance(record)
        record["slots"] = [standing, far]
        record["seats"] = [standing]
        with self.assertRaisesRegex(ValueError, "vacant place directly above"):
            build.validate_clearance(record)

    def test_rejects_repeated_neighbor_on_adjacent_bay_edge(self):
        north1 = {"bayId": 1, "stand": "north", "capacity": 3,
                  "seats": [seat(12, 35, 25, "pie"), seat(12, 138, 25, "horn")]}
        north2 = {"bayId": 2, "stand": "north", "capacity": 3,
                  "seats": [seat(22, 14, 25, "standing-horn", feet=[position(14, 25), position(22, 25)]),
                            seat(22, 45, 25, "clap")]}
        with self.assertRaisesRegex(ValueError, "row 2 repeats horn in bays 1 and 2"):
            build.validate_end_stands([north1, north2], self.bays)

    def test_checks_aggregate_attendance_and_neutral_wardrobes(self):
        north1 = {"bayId": 1, "stand": "north", "capacity": 6,
                  "seats": [seat(12, 35, 25, "pie"), seat(12, 65, 25, "clap"),
                            seat(13, 45, 45, "horn"), seat(13, 110, 45, "lookout")]}
        build.validate_end_stands([north1], self.bays)
        wrong_count = deepcopy(north1)
        wrong_count["seats"] = wrong_count["seats"][:2]
        with self.assertRaisesRegex(ValueError, "two-thirds occupancy"):
            build.validate_end_stands([wrong_count], self.bays)
        for supporter in north1["seats"]:
            supporter["neutral"] = True
        with self.assertRaisesRegex(ValueError, "one-third neutral"):
            build.validate_end_stands([north1], self.bays)

    def test_rejects_head_occlusion_at_close_anchors(self):
        plan = {"bayId": 1, "rows": [{"plank": "row-2", "anchors": [[50, 25], [52, 25]],
                                     "occupants": {"0": "horn", "1": "clap"}}]}
        with self.assertRaisesRegex(ValueError, "covers a face, gesture, or standing sole"):
            build.build_bay(plan, self.bays[1], self.supports[1], self.mask, self.tiles, self.landmarks)

    def test_rejects_raw_alpha_outside_bay_before_indexed_paste(self):
        plan = {"bayId": 1, "rows": [{"plank": "row-2", "anchors": [[34, 25]], "occupants": {"0": "horn"}}]}
        damaged = self.tiles.copy()
        indexed = damaged["north-horn"][0].copy()
        indexed.putpixel((0, 0), 33)
        rgba = indexed.convert("RGBA")
        damaged["north-horn"] = (indexed, rgba, rgba.getchannel("A"))
        with self.assertRaisesRegex(ValueError, "north-horn crosses bay 1"):
            build.build_bay(plan, self.bays[1], self.supports[1], self.mask, damaged, self.landmarks)

    def test_rejects_vacancy_without_full_seated_fit(self):
        plan = {"bayId": 1, "rows": [
            {"plank": "row-2", "anchors": [[34, 25]], "occupants": {"0": "horn"}},
            {"plank": "row-1", "anchors": [[72, 4]], "occupants": {}},
        ]}
        with self.assertRaisesRegex(ValueError, "has no seated pose fitting vacant place"):
            build.build_bay(plan, self.bays[1], self.supports[1], self.mask, self.tiles, self.landmarks)


if __name__ == "__main__":
    unittest.main()
