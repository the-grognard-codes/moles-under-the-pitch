"""Export the approved native Gatehouse art and its overhead view manifest."""

from hashlib import sha256
import json
from pathlib import Path
from shutil import copyfile

from PIL import Image


GAME = Path(__file__).resolve().parents[1]
SOURCE = GAME / "references/stadiums/scale-studies"
TARGET = GAME / "pitch/stadiums/gatehouse"
SQUARE = 32
PITCH = {"x": 156, "y": 169, "width": 15 * SQUARE, "height": 26 * SQUARE}


def read(path):
    return json.loads((SOURCE / path).read_text(encoding="utf-8"))


def write(path, value):
    (TARGET / path).write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8", newline="\n")


def crowd_atlas(seating, size):
    art = Image.new("RGBA", size)
    materials = Image.new("L", size)
    seating_mask = Image.open(SOURCE / seating["seatingMask"])
    if seating_mask.size != size:
        raise ValueError("Seating mask differs from the Gatehouse canvas")
    rectangles = {bay["id"]: bay for bay in seating["bays"]}
    bays = []
    sources = [seating["seatingMask"], "empty-seating.json"]
    for directory in ("end-stands/full-ends", "side-stands"):
        placements = directory + "/placements.json"
        sources.append(placements)
        for bay in read(placements):
            source = directory + "/" + bay["crowd"]
            sources.append(source)
            bounds = rectangles[bay["bayId"]]
            x, y, width, height = (bounds[key] for key in ("x", "y", "width", "height"))
            indexed = Image.open(SOURCE / source)
            if indexed.mode != "P" or indexed.size != (width, height) or indexed.info.get("transparency") != 0:
                raise ValueError(f"Expected approved indexed art for {source}")
            rgba = indexed.convert("RGBA")
            for index, bay_id in zip(indexed.tobytes(), seating_mask.crop((x, y, x + width, y + height)).tobytes()):
                if index and bay_id != bounds["id"]:
                    raise ValueError(f"Visible spectator outside seating mask in {source}")
            art.paste(rgba, (x, y), rgba.getchannel("A"))
            indices = bytes(index if 1 <= index <= 32 else 0 for index in indexed.tobytes())
            materials.paste(Image.frombytes("L", indexed.size, indices), (x, y))
            bays.append({"id": bounds["id"], "stand": bounds["stand"], "x": x, "y": y,
                         "width": width, "height": height})
    if sorted(bay["id"] for bay in bays) != sorted(rectangles):
        raise ValueError("Expected exactly one insert in each approved seating bay")
    art.save(TARGET / "crowd.png", optimize=True)
    materials.save(TARGET / "crowd-materials.png", optimize=True)
    return sorted(bays, key=lambda bay: bay["id"]), sources


def view(end, bays, regalia, paints):
    away = end == "away"
    kits = {bay["id"]: ("away" if (bay["stand"] == ("west" if away else "east")) else "home") for bay in bays}
    pieces = []
    for piece in regalia:
        sprite, owner = piece["sprite"], piece["owner"]
        if away and "-pennant-" in sprite:
            owner = "away" if owner == "home" else "home"
            sprite = ("orc" if owner == "away" else "human") + sprite[sprite.index("-pennant-"):]
        pieces.append({key: piece[key] for key in ("x", "y")} | {"sprite": sprite, "team": owner})
    return {
        "alignment": {
            "pitchOrigin": [PITCH["x"], PITCH["y"]],
            "worldOrigin": [0, 15] if away else [26, 0],
            "worldRight": [0, -1] if away else [0, 1],
            "worldDown": [1, 0] if away else [-1, 0],
            "pixelsPerSquare": SQUARE,
        },
        "layers": "approved",
        "crowd": "full",
        "regalia": end,
        "kitByBay": kits,
        "endZones": [paint | {"team": team} for paint, team in
                     zip(paints, ("home", "away") if away else ("away", "home"))],
    }, pieces


def build():
    TARGET.mkdir(parents=True, exist_ok=True)
    seating = read("empty-seating.json")
    canvas = {key: seating["nativeCanvas"][key] for key in ("width", "height")}
    stairs = read("locker-stairwell.json")
    regalia_atlas = read("regalia/atlas.json")
    regalia_layout = read("regalia/layout.json")
    bays, sources = crowd_atlas(seating, (canvas["width"], canvas["height"]))
    paints = [
        {"x": PITCH["x"], "y": y, "width": PITCH["width"], "height": SQUARE, "seed": seed}
        for y, seed in ((PITCH["y"], 572), (PITCH["y"] + PITCH["height"] - SQUARE, 573))
    ]
    views, regalia_sets = {}, {}
    for end in ("home", "away"):
        views[end], pieces = view(end, bays, regalia_layout["placements"], paints)
        regalia_sets[end] = {"atlas": "approved", "pieces": pieces}
    copies = {
        "architecture.webp": seating["stadiumArtwork"],
        "stairs.png": "locker-stairwell.png",
        "regalia.png": "regalia/atlas.png",
        "regalia-materials.png": "regalia/materials.png",
    }
    for destination, source in copies.items():
        copyfile(SOURCE / source, TARGET / destination)
    layers = [{"kind": "ground"}]
    for placement in stairs["placements"]:
        position = {key: placement[key] for key in ("x", "y", "width", "height", "rotate180")}
        layers.append({"kind": "image", "art": "stairs.png", **position})
    layers.append({"kind": "image", "art": "architecture.webp", "x": 0, "y": 0, **canvas})
    write("views.json", {
        "version": 1,
        "canvas": canvas,
        "layers": {"approved": layers},
        "crowds": {"full": {"art": "crowd.png", "materials": "crowd-materials.png", "bays": bays}},
        "regaliaAtlases": {"approved": {"art": "regalia.png", "materials": "regalia-materials.png", **regalia_atlas}},
        "regaliaSets": regalia_sets,
        "views": views,
    })
    write("provenance.json", {
        "approval": "https://github.com/the-grognard-codes/moles-under-the-pitch/pull/580",
        "sourceRoot": SOURCE.relative_to(GAME).as_posix(),
        "sources": {source: sha256((SOURCE / source).read_bytes()).hexdigest() for source in
                    sorted(set(sources + list(copies.values()) + ["locker-stairwell.json",
                                                               "regalia/atlas.json", "regalia/layout.json"]))},
        "runtime": {name: sha256((TARGET / name).read_bytes()).hexdigest() for name in
                    sorted([*copies, "crowd.png", "crowd-materials.png", "views.json"])},
    })


if __name__ == "__main__":
    build()
