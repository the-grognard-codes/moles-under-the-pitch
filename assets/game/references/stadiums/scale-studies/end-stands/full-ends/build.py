"""Place the full north and south crowds on the approved native bay footprints."""
from collections import defaultdict
from importlib.util import module_from_spec, spec_from_file_location
import json
from pathlib import Path

from PIL import Image


HERE = Path(__file__).resolve().parent
BAY_IDS = (1, 2, 3, 12, 13, 14, 15)
STANDING_ALIGNMENT = 15

spec = spec_from_file_location("end_stand_proofs", HERE.parent / "build.py")
proof = module_from_spec(spec)
spec.loader.exec_module(proof)


def team_ramps():
    path = proof.ART.parents[2] / "team-colors/v1/delivery/manifest.json"
    return {ramp["id"]: ramp["colors"] for ramp in proof.read(path)["ramps"]}


def home_palette(source, ramps):
    palette = source.copy()
    for start, material in ((1, "navy"), (17, "gold")):
        palette[3 * start:3 * (start + 16)] = bytes.fromhex("".join(color[1:] for color in ramps[material]))
    return palette


def pose_art(atlas):
    size = (len(proof.COLUMNS) * proof.CELL_SIZE, proof.CELL_SIZE)
    if atlas.mode != "P" or atlas.size != size or atlas.info.get("transparency") != 0:
        raise ValueError("The end supporter atlas must be indexed with transparent index 0")
    palette = team_ramps()
    tiles = {}
    for column, pose in enumerate(proof.COLUMNS):
        left = column * proof.CELL_SIZE
        indexed = atlas.crop((left, 0, left + proof.CELL_SIZE, proof.CELL_SIZE))
        rgba = proof.spectator(atlas, pose, palette)
        if indexed.convert("RGBA").tobytes() != rgba.tobytes():
            raise ValueError(f"{pose} atlas palette differs from the established end-stand colors")
        tiles[pose] = (indexed, rgba, rgba.getchannel("A"))
    return tiles


def fit_pose(pose, anchor, footprint, mask, bay, tiles, landmarks):
    _, rgba, _ = tiles[pose]
    marks = landmarks[pose]
    direction = 1 if bay["stand"] == "north" else -1
    origin, attachments = proof.place(rgba, pose, anchor, direction, marks, footprint)
    visible = proof.checked_pixels(rgba, origin, mask, bay, pose)
    return origin, attachments, visible


def validate_clearance(record):
    rows = defaultdict(list)
    for slot in record["slots"]:
        rows[slot["plankId"]].append(slot["slot"])
    occupied = {(seat["x"], seat["y"]) for seat in record["seats"]}
    for seat in record["seats"]:
        if "feet" not in seat:
            continue
        upper_row = max((plank for plank in rows if plank < seat["plankId"]), default=None)
        above = [slot for slot in rows.get(upper_row, []) if slot["y"] < seat["y"]]
        nearest = min((abs(slot["x"] - seat["x"]) for slot in above), default=STANDING_ALIGNMENT + 1)
        aligned = [(slot["x"], slot["y"]) for slot in above if abs(slot["x"] - seat["x"]) == nearest]
        if nearest > STANDING_ALIGNMENT or any(slot in occupied for slot in aligned):
            raise ValueError(f"Bay {record['bayId']} {seat['pose']} needs a vacant place directly above ({seat['x']}, {seat['y']})")


def validate_end_stands(records, bays):
    benches = defaultdict(list)
    ends = defaultdict(list)
    for record in records:
        ends[record["stand"]].append(record)
        bay = bays[record["bayId"]]
        for seat in record["seats"]:
            identity = seat["supporter"].removeprefix("standing-")
            benches[record["stand"], seat["plankId"] % 10].append(
                (bay["x"] + seat["x"], record["bayId"], identity))

    for stand, records in ends.items():
        capacity = sum(record["capacity"] for record in records)
        occupied = sum(len(record["seats"]) for record in records)
        neutral = sum(seat["neutral"] for record in records for seat in record["seats"])
        if abs(3 * occupied - 2 * capacity) > 3:
            raise ValueError(f"{stand} needs two-thirds occupancy within one seat ({occupied}/{capacity})")
        if abs(3 * neutral - occupied) > 3:
            raise ValueError(f"{stand} needs one-third neutral supporters within one person ({neutral}/{occupied})")

    for (stand, row), seats in benches.items():
        left_to_right = sorted(seats)
        for (_, bay1, left), (_, bay2, right) in zip(left_to_right, left_to_right[1:]):
            if abs(bay1 - bay2) <= 1 and left == right:
                raise ValueError(f"{stand} row {row} repeats {left} in bays {bay1} and {bay2}")


def build_bay(layout, bay, supports, mask, tiles, landmarks):
    stand, bay_id = bay["stand"], bay["id"]
    seated = [pose for pose in tiles if pose.startswith(f"{stand}-") and "knee" in landmarks[pose]]
    anchors, planks, slots, prepared = set(), set(), [], []
    for row in layout["rows"]:
        plank, footprint = supports[row["plank"]]
        if plank in planks:
            raise ValueError(f"Bay {bay_id} lists plank {plank} twice")
        planks.add(plank)
        occupants = row["occupants"]
        if not set(occupants).issubset(str(i) for i in range(len(row["anchors"]))):
            raise ValueError(f"Bay {bay_id} occupies an unplanned place on plank {plank}")

        for index, anchor in enumerate(row["anchors"]):
            anchor = tuple(anchor)
            if anchor in anchors:
                raise ValueError(f"Bay {bay_id} repeats native place {anchor}")
            anchors.add(anchor)
            slot = {"plankId": plank, "slot": proof.point(anchor)}
            slots.append(slot)
            supporter = occupants.get(str(index))
            if supporter is None:
                check_vacancy(anchor, footprint, mask, bay, tiles, landmarks, seated)
                continue

            pose = f"{stand}-{supporter}"
            if pose not in tiles:
                raise ValueError(f"Bay {bay_id} uses unknown supporter {supporter}")
            origin, attachments, visible = fit_pose(pose, anchor, footprint, mask, bay, tiles, landmarks)
            marks = landmarks[pose]
            face = proof.screened_region(visible, origin, marks["face"])
            gesture = proof.screened_region(visible, origin, marks.get("gesture"))
            if not face or ("gesture" in marks and not gesture):
                raise ValueError(f"{pose} has an empty face or gesture probe")
            baseline = max(y for _, y in visible)
            features = face | gesture
            if "feet" in attachments:
                features.update((x, y) for x, y in visible if y == baseline)
            seat = dict(slot, supporter=supporter, pose=pose, **proof.point(anchor), **attachments,
                        neutral=supporter in proof.NEUTRAL)
            prepared.append((baseline, anchor[0], seat, origin, visible, features))

    if not slots or not prepared:
        raise ValueError(f"Bay {bay_id} needs registered places and supporters")

    crowd = Image.new("P", (bay["width"], bay["height"]))
    crowd.putpalette(next(iter(tiles.values()))[0].getpalette())
    protected = set()
    seats = []
    for _, _, seat, origin, visible, features in sorted(prepared, key=lambda entry: entry[:2]):
        if visible & protected:
            raise ValueError(f"Bay {bay_id} {seat['pose']} at ({seat['x']}, {seat['y']}) covers a face, gesture, or standing sole")
        indexed, _, alpha = tiles[seat["pose"]]
        crowd.paste(indexed, origin, alpha)
        protected.update(features)
        seats.append(seat)

    record = {"bayId": bay_id, "stand": stand, "capacity": len(slots), "slots": slots,
              "seats": seats, "crowd": f"bay-{bay_id:02}.png"}
    validate_clearance(record)
    return crowd, record


def check_vacancy(anchor, footprint, mask, bay, tiles, landmarks, seated):
    for pose in seated:
        try:
            fit_pose(pose, anchor, footprint, mask, bay, tiles, landmarks)
        except ValueError:
            continue
        return
    raise ValueError(f"Bay {bay['id']} has no seated pose fitting vacant place {anchor}")


def render(layout, mask, bays, supports, tiles, landmarks):
    if sorted(record["bayId"] for record in layout) != list(BAY_IDS):
        raise ValueError("The full end layout must contain all seven end bays exactly once")
    results = []
    palette = home_palette(next(iter(tiles.values()))[0].getpalette(), team_ramps())
    for entry in sorted(layout, key=lambda record: record["bayId"]):
        bay_id = entry["bayId"]
        crowd, record = build_bay(entry, bays[bay_id], supports[bay_id], mask, tiles, landmarks)
        # The native indices and transparent/skin/prop/wardrobe entries are unchanged.
        crowd.putpalette(palette)
        results.append((crowd, record))
    validate_end_stands([record for _, record in results], bays)
    return results


def build():
    mask, bays, supports = proof.geometry()
    layout = proof.read(HERE / "layout.json")
    landmarks = proof.read(HERE.parent / "pose-landmarks.json")
    with Image.open(HERE.parent / "supporters.png") as atlas:
        results = render(layout, mask, bays, supports, pose_art(atlas), landmarks)
    for crowd, record in results:
        path = HERE / record["crowd"]
        crowd.save(path, optimize=True, transparency=0)
        proof.fit.check_insert(mask, bays[record["bayId"]], path)
    manifest = json.dumps([record for _, record in results], separators=(",", ":"))
    (HERE / "placements.json").write_text(manifest + "\n", encoding="utf-8")


if __name__ == "__main__":
    build()
