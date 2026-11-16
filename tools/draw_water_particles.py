"""Draw LiteLights' water particle textures: splash droplets, the splash foam ring, ripples,
waterfall foam and mist. Pixel art at vanilla particle resolution (8 x 8, rings 16 x 16), in the
same whites and pale blues as the water foam the shaders draw.

The Advanced jump splash's square ring and rising splash are drawn at one pixel per sixteenth of a
block, a frame per size, so they keep the blocks' pixel size as they grow: the ring 88 x 88 (a frame
for every two pixels of reach), the rising splash 48 x 48 (a frame per height, standing on the
bottom row). Water-blue with white edges.

Usage: python3 -I tools/draw_water_particles.py
Writes into src/main/resources/assets/litelights/textures/particle/. Deterministic: the same
files come out every run."""
from pathlib import Path
import math
import random

from PIL import Image

OUT = Path(__file__).resolve().parent.parent / "src/main/resources/assets/litelights/textures/particle"

FOAM = (240, 247, 255, 255)
LIGHT = (208, 228, 244, 255)
MID = (172, 203, 230, 235)
DROP = (186, 216, 241, 235)
HIGHLIGHT = (255, 255, 255, 255)

PALETTE = {".": None, "H": HIGHLIGHT, "W": FOAM, "L": LIGHT, "M": MID, "D": DROP}

DROPLETS = [
    [
        "........",
        "...HD...",
        "..HDDD..",
        "..DDDM..",
        "..DDDM..",
        "...MM...",
        "........",
        "........",
    ],
    [
        "........",
        "........",
        "...HD...",
        "...DDM..",
        "...DM...",
        "........",
        "........",
        "........",
    ],
    [
        "........",
        "........",
        "........",
        "...HD...",
        "...DM...",
        "........",
        "........",
        "........",
    ],
    [
        "........",
        "........",
        "........",
        "....D...",
        "........",
        "........",
        "........",
        "........",
    ],
]


def from_rows(rows):
    image = Image.new("RGBA", (len(rows[0]), len(rows)), (0, 0, 0, 0))
    for y, row in enumerate(rows):
        for x, key in enumerate(row):
            colour = PALETTE[key]
            if colour:
                image.putpixel((x, y), colour)
    return image


def ring(frame, radius, thickness, gap_share, colours, seed):
    """A ring of foam pixels around the centre of a 16 x 16 tile, breaking up as it ages."""
    rng = random.Random(seed)
    sectors = [rng.random() for _ in range(24)]
    image = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    for y in range(16):
        for x in range(16):
            dx, dy = x + 0.5 - 8.0, y + 0.5 - 8.0
            distance = math.hypot(dx, dy)
            offset = distance - radius
            if abs(offset) > thickness:
                continue
            sector = int((math.atan2(dy, dx) + math.pi) / (2 * math.pi) * len(sectors)) % len(sectors)
            if sectors[sector] < gap_share:
                continue
            outer = offset > thickness * 0.3
            image.putpixel((x, y), colours[1] if outer else colours[0])
    return image


def foam(frame):
    """A cluster of foam bubbles, thinning out frame by frame."""
    rng = random.Random(41 + frame)
    image = Image.new("RGBA", (8, 8), (0, 0, 0, 0))
    bubbles = [(3.5, 3.5, 2.2), (5.2, 2.6, 1.3), (2.0, 5.4, 1.2), (5.6, 5.3, 1.0)]
    keep = len(bubbles) - frame
    for cx, cy, r in bubbles[:keep]:
        r = r * (1.0 - 0.15 * frame)
        for y in range(8):
            for x in range(8):
                d = math.hypot(x + 0.5 - cx, y + 0.5 - cy)
                if d <= r:
                    edge = d > r - 0.8
                    colour = LIGHT if edge else FOAM
                    if frame == 2 and rng.random() < 0.3:
                        continue
                    image.putpixel((x, y), colour)
    return image


def mist(frame):
    """A soft puff in three alpha steps, wider and fainter frame by frame."""
    image = Image.new("RGBA", (8, 8), (0, 0, 0, 0))
    radius = 2.6 + 0.6 * frame
    strength = 1.0 - 0.25 * frame
    for y in range(8):
        for x in range(8):
            d = math.hypot(x + 0.5 - 4.0, y + 0.5 - 4.0) / radius
            if d > 1.0:
                continue
            level = 150 if d < 0.45 else 95 if d < 0.75 else 45
            # A checkerboard on the outer step keeps the edge soft without blending.
            if d >= 0.75 and (x + y) % 2 == 1:
                continue
            image.putpixel((x, y), (255, 255, 255, int(level * strength)))
    return image


WATER_BLUE = (74, 136, 214, 235)
DEEP_BLUE = (52, 104, 186, 225)
EDGE_WHITE = (244, 250, 255, 255)
SQUARE_SIZE = 88
SQUARE_FRAMES = 20
CROWN_SIZE = 48
CROWN_FRAMES = 12


def square_ring(frame):
    """A square ring of water around the centre, white on both edges, breaking up as it spreads."""
    rng = random.Random(101 + frame)
    image = Image.new("RGBA", (SQUARE_SIZE, SQUARE_SIZE), (0, 0, 0, 0))
    centre = SQUARE_SIZE / 2.0
    reach = 3.0 + 2.0 * frame
    age = frame / (SQUARE_FRAMES - 1)
    # Gaps along the ring: runs of a few pixels missing, more of them as it ages.
    gaps = set()
    perimeter = int(8 * reach) + 8
    position = rng.randrange(6)
    while position < perimeter:
        if rng.random() < 0.06 + 0.32 * age:
            run = rng.randint(2, 4 + int(4 * age))
            gaps.update(range(position, position + run))
            position += run
        position += rng.randint(3, 7)
    for y in range(SQUARE_SIZE):
        for x in range(SQUARE_SIZE):
            dx, dy = x + 0.5 - centre, y + 0.5 - centre
            distance = max(abs(dx), abs(dy))
            offset = distance - reach
            if offset < -2.0 or offset >= 2.0:
                continue
            # Walk the square's outline to place the gaps.
            if abs(dx) >= abs(dy):
                along = (dy + reach) if dx > 0 else (5 * reach - dy)
            else:
                along = (3 * reach - dx) if dy > 0 else (7 * reach + dx)
            if int(along) % max(perimeter, 1) in gaps:
                continue
            if offset >= 1.0:
                colour = EDGE_WHITE
            elif offset >= -1.0:
                colour = WATER_BLUE if offset >= 0.0 else DEEP_BLUE
            else:
                # The inner edge fades first as the ring thins out.
                if rng.random() < age * 0.7:
                    continue
                colour = EDGE_WHITE
            image.putpixel((x, y), colour)
    return image


def splash_crown(frame):
    """Water thrown up from the landing point: a plume with two side jets over a foam mound."""
    rng = random.Random(211 + frame)
    image = Image.new("RGBA", (CROWN_SIZE, CROWN_SIZE), (0, 0, 0, 0))
    centre = CROWN_SIZE / 2.0
    height = 4 + 3.6 * frame
    bottom = CROWN_SIZE - 1

    def put(x, y, colour):
        if 0 <= x < CROWN_SIZE and 0 <= y < CROWN_SIZE:
            image.putpixel((x, y), colour)

    # The plume: wider at the base, narrowing to the top.
    for row in range(int(height)):
        y = bottom - row
        rise = row / max(height - 1, 1)
        half = (2.0 + frame * 0.35) * (1.0 - 0.7 * rise) + 0.5
        left = int(round(centre - half))
        right = int(round(centre + half)) - 1
        for x in range(left, right + 1):
            put(x, y, WATER_BLUE if (x + row) % 5 else DEEP_BLUE)
        put(left, y, EDGE_WHITE)
        put(right, y, EDGE_WHITE)
    top = bottom - int(height) + 1
    for x in range(int(centre) - 1, int(centre) + 1):
        put(x, top, EDGE_WHITE)
        put(x, top - 1, EDGE_WHITE)
    # Two side jets leaning outward, about half as tall.
    jet = height * 0.55
    for side in (-1, 1):
        for row in range(int(jet)):
            y = bottom - row
            lean = (row / max(jet, 1)) ** 1.6 * (3 + frame * 0.9)
            x = int(round(centre + side * (3 + frame * 0.45 + lean)))
            put(x, y, WATER_BLUE)
            put(x + side, y, EDGE_WHITE)
        tip_y = bottom - int(jet)
        tip_x = int(round(centre + side * (3 + frame * 0.45 + 3 + frame * 0.9)))
        put(tip_x, tip_y, EDGE_WHITE)
    # Drops thrown off the top.
    for _ in range(1 + frame // 3):
        x = int(centre + rng.uniform(-2 - frame * 0.4, 2 + frame * 0.4))
        y = top - 2 - rng.randint(0, 2 + frame // 2)
        put(x, y, EDGE_WHITE)
    # The foam mound at the foot.
    width = 4 + frame * 0.8
    for row in range(3):
        y = bottom - row
        half = width * (1.0 - 0.3 * row)
        for x in range(int(centre - half), int(centre + half)):
            put(x, y, EDGE_WHITE if row == 2 or (x + row) % 3 == 0 else (226, 238, 252, 255))
    return image


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    for frame in range(SQUARE_FRAMES):
        square_ring(frame).save(OUT / f"splash_square_{frame}.png")
    for frame in range(CROWN_FRAMES):
        splash_crown(frame).save(OUT / f"splash_crown_{frame}.png")
    for index, rows in enumerate(DROPLETS):
        from_rows(rows).save(OUT / f"splash_droplet_{index}.png")
    for frame in range(4):
        image = ring(frame, 6.0, 1.3 - 0.25 * frame, 0.08 + 0.18 * frame, (FOAM, LIGHT), 7)
        image.save(OUT / f"splash_ring_{frame}.png")
    for frame in range(2):
        colour = (LIGHT[0], LIGHT[1], LIGHT[2], 210 - 70 * frame)
        edge = (MID[0], MID[1], MID[2], 170 - 60 * frame)
        ring(frame, 6.8, 0.55, 0.12 + 0.25 * frame, (colour, edge), 13).save(OUT / f"splash_ripple_{frame}.png")
    for frame in range(3):
        foam(frame).save(OUT / f"water_foam_{frame}.png")
        mist(frame).save(OUT / f"water_mist_{frame}.png")
    print("wrote", len(list(OUT.glob("*.png"))), "textures to", OUT)


if __name__ == "__main__":
    main()
