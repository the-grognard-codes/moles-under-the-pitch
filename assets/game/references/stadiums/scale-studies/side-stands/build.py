"""Build Gatehouse side stands using the approved profile supporter artwork."""
from collections import defaultdict
from graphlib import CycleError, TopologicalSorter
from importlib.util import module_from_spec, spec_from_file_location
from itertools import combinations
import json
from pathlib import Path
from typing import NamedTuple

from PIL import Image, ImageOps


HERE = Path(__file__).resolve().parent
BAY_IDS = tuple(range(4, 12))
KITS = {"west": ("navy", "gold"), "east": ("red", "gold")}
NEIGHBOR_DISTANCE = 60

spec = spec_from_file_location("west_supporters", HERE.parent / "bay-six/build.py")
west = module_from_spec(spec)
spec.loader.exec_module(west)


class Tile(NamedTuple):
    indexed: Image.Image
    rgba: Image.Image
    marks: dict


class Placement(NamedTuple):
    seat: dict
    raw: set
    visible: set
    features: set
    origin: tuple
    matte: Image.Image


def read(path):
    return json.loads(path.read_text(encoding="utf-8"))


def geometry():
    mask = west.fit.build_mask()
    manifest = west.fit.check_seating(mask)
    _, supports = west.fit.bench_guide(mask, manifest)
    return mask, {bay["id"]: bay for bay in manifest["bays"]}, supports


def mirrored_marks(marks):
    size = west.CELL_SIZE
    mirrored = {}
    for key in ("contact", "knee", "otherSole"):
        if key in marks:
            x, y = marks[key]
            mirrored[key] = (size - 1 - x, y)
    for key in ("face", "gesture"):
        left, top, right, bottom = marks[key]
        mirrored[key] = (size - right, top, size - left, bottom)
    if "behindSeat" in marks:
        mirrored["behindSeat"] = [(size - x - w, y, w, h) for x, y, w, h in marks["behindSeat"]]
        # footFronts marks a two-pixel horizontal toe segment from its left edge.
        mirrored["footFronts"] = [(size - x - 2, y) for x, y in marks["footFronts"]]
    return mirrored


def load_art():
    landmarks = read(west.HERE / "pose-landmarks.json")
    art = {stand: {} for stand in KITS}
    with Image.open(west.HERE / "supporters.png") as atlas:
        size = west.CELL_SIZE
        if atlas.mode != "P" or atlas.size != (size * len(west.COLUMNS), size) or atlas.info.get("transparency") != 0:
            raise ValueError("Side supporters must use the approved indexed atlas with transparent index 0")
        for column, pose in enumerate(west.COLUMNS):
            tile = atlas.crop((size * column, 0, size * (column + 1), size))
            marks = landmarks[pose]
            art["west"][pose] = Tile(tile, tile.convert("RGBA"), marks)
            facing_pitch = ImageOps.mirror(tile)
            art["east"][pose] = Tile(facing_pitch, facing_pitch.convert("RGBA"), mirrored_marks(marks))
    return art


def fit_pose(pose, anchor, plank, mask, bay, supports, art):
    """Register and validate an untrimmed tile, then calculate its own timber tuck."""
    stand = bay["stand"]
    tile = art[stand][pose]
    marks = tile.marks
    x, y = anchor
    facing = 1 if stand == "west" else -1
    measured, origin = west.place_pose(tile.rgba, pose, plank, x, y, marks, supports, facing=facing)
    raw = west.checked_pixels(tile.rgba, origin, mask, bay, f"Bay {bay['id']} {pose}")

    cutout = tile.rgba.copy()
    if "behindSeat" in marks:
        west.tuck_behind_seat(cutout, origin, supports[plank][1], marks["behindSeat"])
    matte = cutout.getchannel("A")
    ox, oy = origin
    visible = {(px, py) for px, py in raw if matte.getpixel((px - ox, py - oy))}
    features = set()
    for key in ("face", "gesture"):
        left, top, right, bottom = marks[key]
        probe = {(px, py) for px, py in visible if left <= px - ox < right and top <= py - oy < bottom}
        if not probe:
            raise ValueError(f"Bay {bay['id']} {pose} has an empty {key} probe")
        features.update(probe)

    seat = {"pose": pose, "supporter": pose.split("-", 1)[1], "plankId": measured["plankId"], "x": x, "y": y,
            "neutral": west.WARDROBES[pose] == "neutral"}
    if "otherSole" in measured:
        seat["feet"] = [{"x": x, "y": y}, measured["otherSole"]]
        baseline = max(py for _, py in raw)
        features.update((px, py) for px, py in visible if py == baseline)
    else:
        seat["knee"] = measured["knee"]
    return Placement(seat, raw, visible, features, origin, matte)


def point(anchor):
    x, y = anchor
    return {"x": x, "y": y}


def fit_vacancy(anchor, plank, mask, bay, supports, art):
    for pose in west.COLUMNS:
        if pose.startswith("seated-"):
            try:
                fit_pose(pose, anchor, plank, mask, bay, supports, art)
            except ValueError:
                continue
            return
    raise ValueError(f"Bay {bay['id']} {plank} has no full seated pose fitting vacant slot {anchor}")


def depth_order(placements):
    """Resolve overlapping features at projected boot baselines within one pixel."""
    ordered = sorted(placements, key=lambda fitted: max(y for _, y in fitted.raw))
    baselines = [max(y for _, y in fitted.raw) for fitted in ordered]
    predecessors = [set() for _ in ordered]
    for i, behind in enumerate(ordered):
        for j in range(i + 1, len(ordered)):
            front = ordered[j]
            if baselines[j] - baselines[i] > 1:
                predecessors[j].add(i)
                continue
            if behind.features & front.visible:
                predecessors[i].add(j)
            if front.features & behind.visible:
                predecessors[j].add(i)
    try:
        return [ordered[index] for index in TopologicalSorter(dict(enumerate(predecessors))).static_order()]
    except CycleError as error:
        poses = (ordered[index].seat["pose"] for index in error.args[1][:-1])
        raise ValueError(f"Nearby poses cannot all keep their faces and props visible: {', '.join(poses)}") from error


def build_bay(layout, mask, bay, supports, art):
    bay_id, stand = bay["id"], bay["stand"]
    planks, positions, slots, placements = set(), set(), [], []
    for row in layout["rows"]:
        plank = row["plank"]
        plank_id, _ = supports[plank]
        if plank in planks:
            raise ValueError(f"Bay {bay_id} lists {plank} twice")
        planks.add(plank)

        anchors = row["anchors"]
        occupants = row["occupants"]
        standing_contacts = row.get("standingContacts", {})
        if not set(occupants).issubset(str(i) for i in range(len(anchors))):
            raise ValueError(f"Bay {bay_id} occupies an unplanned slot on {plank}")
        if any(not occupants.get(key, "").startswith("standing-") for key in standing_contacts):
            raise ValueError(f"Bay {bay_id} assigns a standing contact to an unoccupied or seated slot on {plank}")

        order = sorted(range(len(anchors)), key=lambda index: anchors[index][1])
        if len(order) != len({anchor[1] for anchor in anchors}):
            raise ValueError(f"Bay {bay_id} lists two slots on {plank} at the same y")
        for n, index in enumerate(order):
            anchor = tuple(anchors[index])
            if anchor in positions:
                raise ValueError(f"Bay {bay_id} repeats permanent slot {anchor}")
            positions.add(anchor)
            slots.append({"plankId": plank_id, "slot": point(anchor)})
            key = str(index)
            pose = occupants.get(key)
            if pose is None:
                fit_vacancy(anchor, plank, mask, bay, supports, art)
                continue
            if pose not in art[stand]:
                raise ValueError(f"Bay {bay_id} uses unknown profile pose {pose}")
            if pose.startswith("standing-"):
                if n == 0 or str(order[n - 1]) in occupants:
                    raise ValueError(f"Bay {bay_id} {pose} needs the preceding physical slot above it to be vacant")
                # The permanent slot remains valid as a seat when this fan sits down.
                fit_pose(f"seated-{pose.split('-', 1)[1]}", anchor, plank, mask, bay, supports, art)
            actual = tuple(standing_contacts.get(key, anchor))
            fitted = fit_pose(pose, actual, plank, mask, bay, supports, art)
            fitted.seat["slot"] = point(anchor)
            placements.append(fitted)

    if not slots or not placements:
        raise ValueError(f"Bay {bay_id} needs registered slots and supporters")
    crowd = Image.new("P", (bay["width"], bay["height"]))
    crowd.putpalette(next(iter(art[stand].values())).indexed.getpalette())
    # Projected boot baselines establish depth. All untrimmed tiles fit before any paste.
    placements = depth_order(placements)
    protected = set()
    for fitted in placements:
        seat = fitted.seat
        if fitted.visible & protected:
            raise ValueError(f"Bay {bay_id} {seat['pose']} at ({seat['x']}, {seat['y']}) covers a face, prop or standing sole")
        crowd.paste(art[stand][seat["pose"]].indexed, fitted.origin, fitted.matte)
        protected.update(fitted.features)
    record = {"bayId": bay_id, "stand": stand, "capacity": len(slots), "slots": slots,
              "seats": [fitted.seat for fitted in placements], "crowd": f"bay-{bay_id:02}.png"}
    return crowd, record


def validate_stands(records, bays):
    stands = defaultdict(list)
    rows = defaultdict(list)
    for record in records:
        stand, bay_id = record["stand"], record["bayId"]
        stands[stand].append(record)
        bay = bays[bay_id]
        for left, right in combinations(record["seats"], 2):
            if (left["supporter"] == right["supporter"] and abs(left["plankId"] - right["plankId"]) == 1
                    and abs(left["x"] - right["x"]) <= 22 and abs(left["y"] - right["y"]) <= 24):
                raise ValueError(f"Bay {bay_id} repeats {left['supporter']} across nearby planks")
        for seat in record["seats"]:
            rows[stand, seat["plankId"] % 10].append(
                (bay["y"] + seat["slot"]["y"], bay_id, seat["supporter"]))

    for stand, sections in stands.items():
        capacity = sum(record["capacity"] for record in sections)
        occupied = sum(len(record["seats"]) for record in sections)
        neutral = sum(seat["neutral"] for record in sections for seat in record["seats"])
        if abs(3 * occupied - 2 * capacity) > 3:
            raise ValueError(f"{stand} needs two-thirds occupancy within one seat ({occupied}/{capacity})")
        if abs(3 * neutral - occupied) > 3:
            raise ValueError(f"{stand} needs one-third neutrals within one person ({neutral}/{occupied})")

    for (stand, row), occupants in rows.items():
        ordered = sorted(occupants)
        for (y1, bay1, earlier), (y2, bay2, later) in zip(ordered, ordered[1:]):
            nearby = bay1 == bay2 or (bay2 == bay1 + 1 and y2 - y1 <= NEIGHBOR_DISTANCE)
            if nearby and earlier == later:
                raise ValueError(f"{stand} plank {row} repeats {earlier} between bays {bay1} and {bay2}")


def team_ramps():
    path = west.ART.parents[2] / "team-colors/v1/delivery/manifest.json"
    return {ramp["id"]: ramp["colors"] for ramp in read(path)["ramps"]}


def kit_palette(palette, stand, ramps):
    result = palette.copy()
    for index, name in enumerate(KITS[stand]):
        start = (1 + 16 * index) * 3
        result[start:start + 48] = bytes.fromhex("".join(color[1:] for color in ramps[name]))
    return result


def render(layout, mask, bays, supports, art):
    if sorted(entry["bayId"] for entry in layout) != list(BAY_IDS):
        raise ValueError("The side layout must list all eight east and west bays once")
    ramps = team_ramps()
    results = []
    for entry in sorted(layout, key=lambda entry: entry["bayId"]):
        bay_id = entry["bayId"]
        crowd, record = build_bay(entry, mask, bays[bay_id], supports[bay_id], art)
        crowd.putpalette(kit_palette(crowd.getpalette(), record["stand"], ramps))
        results.append((crowd, record))
    validate_stands([record for _, record in results], bays)
    return results


def build():
    mask, bays, supports = geometry()
    results = render(read(HERE / "layout.json"), mask, bays, supports, load_art())
    for crowd, record in results:
        path = HERE / record["crowd"]
        crowd.save(path, optimize=True, transparency=0)
        west.fit.check_insert(mask, bays[record["bayId"]], path)
    manifest = json.dumps([record for _, record in results], separators=(",", ":"))
    (HERE / "placements.json").write_text(manifest + "\n", encoding="utf-8")


if __name__ == "__main__":
    build()
