from collections import Counter
from copy import deepcopy
import unittest

from PIL import Image, ImageOps

import build


class SideStandTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.mask, cls.bays, cls.supports = build.geometry()
        cls.art = build.load_art()

    def bay_six_plan(self):
        rows = []
        for plank in ("wall-bench", "rear-bench", "inner-bench"):
            slots = [slot for slot in build.west.SEAT_PLAN if slot.support == plank]
            occupants, standing_contacts = {}, {}
            for index, slot in enumerate(slots):
                if slot.first_fill > 66:
                    continue
                stance = "standing" if slot.standing_contact else "seated"
                occupants[str(index)] = f"{stance}-{slot.supporter}"
                if slot.standing_contact:
                    standing_contacts[str(index)] = slot.standing_contact
            rows.append({"plank": plank, "anchors": [(slot.x, slot.y) for slot in slots],
                         "occupants": occupants, "standingContacts": standing_contacts})
        return {"bayId": 6, "rows": rows}

    def test_east_mirrors_every_pose_contact_tuck_and_protected_region(self):
        bay = self.bays[6]
        x, y, w, h = (bay[field] for field in ("x", "y", "width", "height"))
        west = dict(bay, x=0, y=0)
        east = dict(west, stand="east")
        west_mask = self.mask.crop((x, y, x + w, y + h))
        east_mask = ImageOps.mirror(west_mask)
        west_planks = self.supports[6]
        east_planks = {name: (plank_id, ImageOps.mirror(footprint))
                       for name, (plank_id, footprint) in west_planks.items()}
        plank = "rear-bench"
        for pose in self.art["west"]:
            with self.subTest(pose=pose):
                anchor = (19, 52) if pose.startswith("seated-") else (20, 52)
                right = build.fit_pose(pose, anchor, plank, west_mask, west, west_planks, self.art)
                left = build.fit_pose(pose, (w - 1 - anchor[0], anchor[1]), plank, east_mask, east, east_planks, self.art)
                for raw_or_visible in ("raw", "visible", "features"):
                    self.assertEqual({(w - 1 - px, py) for px, py in getattr(right, raw_or_visible)},
                                     getattr(left, raw_or_visible))
                self.assertEqual(ImageOps.mirror(right.matte).tobytes(), left.matte.tobytes())
                if "knee" in right.seat:
                    self.assertEqual(w - 1 - right.seat["knee"]["x"], left.seat["knee"]["x"])
                    self.assertGreater(len(right.raw - right.visible), 0)
                    for toe_x, toe_y in self.art["east"][pose].marks["footFronts"]:
                        actual = left.origin[0] + toe_x, left.origin[1] + toe_y
                        self.assertIn(actual, left.visible)
                        self.assertIn((actual[0] + 1, actual[1]), left.visible)
                else:
                    right_feet = right.seat["feet"]
                    self.assertEqual([{"x": w - 1 - p["x"], "y": p["y"]} for p in right_feet], left.seat["feet"])

    def test_hidden_calf_pixel_must_fit_before_tucking_on_either_side(self):
        for bay_id, contact in ((6, (19, 64)), (10, (47, 64))):
            with self.subTest(bay=bay_id):
                bay = self.bays[bay_id]
                args = (bay, self.supports[bay_id], self.art)
                pose, plank = "seated-horn", "rear-bench"
                person = build.fit_pose(pose, contact, plank, self.mask, *args)
                x, y = min(person.raw - person.visible)
                illegal = self.mask.copy()
                illegal.putpixel((bay["x"] + x, bay["y"] + y), 0)
                with self.assertRaisesRegex(ValueError, f"Bay {bay_id} seated-horn crosses the bay"):
                    build.fit_pose(pose, contact, plank, illegal, *args)

    def test_placement_rejects_invalid_facing(self):
        pose = "standing-horn"
        person = self.art["west"][pose]
        for facing in (-2, 0, 2):
            with self.subTest(facing=facing), self.assertRaisesRegex(ValueError, "facing must be -1 or 1"):
                build.west.place_pose(person.rgba, pose, "rear-bench", 20, 52, person.marks,
                                      self.supports[6], facing=facing)

    def test_approved_bay_six_slots_contacts_and_props_survive(self):
        crowd, record = build.build_bay(self.bay_six_plan(), self.mask, self.bays[6], self.supports[6], self.art)
        self.check_approved_bay_six(record)
        for seat in record["seats"]:
            self.check_visible_probes(6, seat, crowd)

    def check_approved_bay_six(self, record):
        self.assertEqual(9, record["capacity"])
        slots = [{"plankId": self.supports[6][slot.support][0], "slot": {"x": slot.x, "y": slot.y}}
                 for slot in build.west.SEAT_PLAN]
        self.assertCountEqual(slots, record["slots"])
        approved = next(view for view in build.read(build.west.HERE / "seats.json") if view["id"] == "b")
        keys = ("pose", "plankId", "slot", "x", "y", "neutral")
        self.assertCountEqual([{k: seat[k] for k in keys} for seat in approved["seats"]],
                              [{k: seat[k] for k in keys} for seat in record["seats"]])
        standing = next(seat for seat in record["seats"] if "feet" in seat)
        self.assertEqual({"x": 19, "y": 64}, standing["slot"])
        self.assertEqual([{"x": 20, "y": 72}, {"x": 27, "y": 72}], standing["feet"])

    def test_filled_preceding_slot_rejects_standing(self):
        plan = self.bay_six_plan()
        plan["rows"][1]["occupants"]["1"] = "seated-cheer"
        with self.assertRaisesRegex(ValueError, "preceding physical slot above it to be vacant"):
            build.build_bay(plan, self.mask, self.bays[6], self.supports[6], self.art)

    def test_repeated_neighbor_in_another_stance_is_same_supporter(self):
        _, record = build.build_bay(self.bay_six_plan(), self.mask, self.bays[6], self.supports[6], self.art)
        build.validate_stands([record], self.bays)
        modified = deepcopy(record)
        chestnuts = next(seat for seat in modified["seats"] if seat["supporter"] == "chestnuts")
        chestnuts.update(supporter="horn", pose="seated-horn")
        with self.assertRaisesRegex(ValueError, "plank 2 repeats horn"):
            build.validate_stands([modified], self.bays)

    def test_rejects_adjacent_identical_supporters_across_nearby_planks(self):
        _, record = build.build_bay(self.bay_six_plan(), self.mask, self.bays[6], self.supports[6], self.art)
        whistle = next(seat for seat in record["seats"] if seat["supporter"] == "whistle")
        whistle.update(supporter="chestnuts", pose="seated-chestnuts", neutral=True)
        with self.assertRaisesRegex(ValueError, "Bay 6 repeats chestnuts across nearby planks"):
            build.validate_stands([record], self.bays)

    def test_stand_wide_attendance_and_neutral_counts_are_checked(self):
        _, record = build.build_bay(self.bay_six_plan(), self.mask, self.bays[6], self.supports[6], self.art)
        with self.assertRaisesRegex(ValueError, "two-thirds occupancy"):
            build.validate_stands([dict(record, seats=record["seats"][:4])], self.bays)
        for seat in record["seats"]:
            seat["neutral"] = False
        with self.assertRaisesRegex(ValueError, "one-third neutrals"):
            build.validate_stands([record], self.bays)

    def test_exported_bays_use_native_indexed_palettes_and_fit_full_sprites(self):
        layout = build.read(build.HERE / "layout.json")
        manifest = build.read(build.HERE / "placements.json")
        built = build.render(layout, self.mask, self.bays, self.supports, self.art)
        self.assertEqual(list(build.BAY_IDS), [record["bayId"] for record in manifest])
        self.assertEqual(manifest, [record for _, record in built])
        for (expected, record) in built:
            bay_id = record["bayId"]
            stand = record["stand"]
            path = build.HERE / record["crowd"]
            with self.subTest(bay=bay_id), Image.open(path) as crowd:
                build.west.fit.check_insert(self.mask, self.bays[bay_id], path)
                self.assertEqual("P", crowd.mode)
                self.assertEqual(0, crowd.info["transparency"])
                self.assertEqual(expected.tobytes(), crowd.tobytes())
                self.check_palette(stand, crowd)
                self.assertEqual(record["capacity"], len(record["slots"]))
                union = set()
                for seat in record["seats"]:
                    fitted = self.check_visible_probes(bay_id, seat, crowd)
                    union.update(fitted.visible)
                self.assertEqual(len(union), sum(pixel != 0 for pixel in crowd.tobytes()))
                if bay_id == 6:
                    self.check_approved_bay_six(record)
        self.assertEqual(Counter({"west": 4, "east": 4}), Counter(record["stand"] for record in manifest))

    def check_palette(self, stand, crowd):
        source = next(iter(self.art[stand].values())).indexed.getpalette()
        output = crowd.getpalette()
        self.assertEqual(source[:3], output[:3])
        self.assertEqual(source[99:], output[99:])
        ramps = build.team_ramps()
        for first, material in zip((1, 17), build.KITS[stand], strict=True):
            ramp = ["#" + bytes(output[i:i + 3]).hex() for i in range(first * 3, (first + 16) * 3, 3)]
            self.assertEqual(ramps[material], ramp)

    def check_visible_probes(self, bay_id, seat, layer):
        bay = self.bays[bay_id]
        planks = self.supports[bay_id]
        plank = next(name for name, (pid, _) in planks.items() if pid == seat["plankId"])
        fitted = build.fit_pose(seat["pose"], (seat["x"], seat["y"]), plank, self.mask, bay, planks, self.art)
        indexed = self.art[bay["stand"]][seat["pose"]].indexed
        ox, oy = fitted.origin
        for x, y in fitted.features:
            self.assertEqual(indexed.getpixel((x - ox, y - oy)), layer.getpixel((x, y)))
        if seat["neutral"]:
            self.assertTrue(set(indexed.tobytes()).isdisjoint(range(1, 33)))
        return fitted


if __name__ == "__main__":
    unittest.main()
