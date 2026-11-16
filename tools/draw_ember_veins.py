"""Draw LiteLights' ember vein sprites: glowing cracks laid over block faces near fire.

Usage: python3 -I tools/draw_ember_veins.py [--preview <file.png>]

Each sprite is a 16x16 block face's worth of cracks in vanilla's pixel style: one-pixel lines that
wander and fork, white-hot yellow at their cores, orange along them and a few dull red pixels at
their edges, with every other pixel fully clear. The game adds the sprite's colour over the block
(times the vein's warm tint and its pulse), so dark pixels glow less. A fixed seed keeps the output
the same on every run.

Writes src/main/resources/assets/litelights/textures/particle/ember_vein_<n>.png (the particle
atlas loads every texture there). With --preview it also writes the sprites at 8x, alone and over a
plank-like face, on a dark background.
"""
from pathlib import Path
import argparse
import random
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
OUTPUT = ROOT / "src" / "main" / "resources" / "assets" / "litelights" / "textures" / "particle"
SEED = 4417
SIZE = 16
VARIANTS = 4

# Additive colours: the hot core, the body of the crack, and its dull edge.
CORE = (255, 236, 160)
BODY = (255, 150, 60)
EDGE = (150, 52, 24)

STEPS = [(1, 0), (-1, 0), (0, 1), (0, -1)]


def crack(cells, rng, x, y, direction, length, depth):
    """Walks a crack from (x, y): mostly straight on, now and then a sideways jog or a fork."""
    dx, dy = direction
    for _ in range(length):
        if not (0 <= x < SIZE and 0 <= y < SIZE):
            return
        cells[(x, y)] = max(cells.get((x, y), 0), 2 if depth == 0 else 1)
        roll = rng.random()
        if roll < 0.22:
            # A jog to the side keeps the line one pixel wide but crooked, like split grain.
            sx, sy = (dy, dx) if rng.random() < 0.5 else (-dy, -dx)
            x, y = x + sx, y + sy
            if 0 <= x < SIZE and 0 <= y < SIZE:
                cells[(x, y)] = max(cells.get((x, y), 0), 1)
        elif roll < 0.30 and depth < 2:
            sx, sy = (dy, dx) if rng.random() < 0.5 else (-dy, -dx)
            crack(cells, rng, x + sx, y + sy, (sx, sy), rng.randint(2, 5), depth + 1)
        x, y = x + dx, y + dy


def vein(rng):
    """One face: two or three cracks running in from the edges, hottest along the main ones."""
    cells = {}
    starts = rng.sample(range(4), rng.choice([2, 3]))
    for side in starts:
        offset = rng.randint(3, SIZE - 4)
        if side == 0:
            x, y, direction = offset, 0, (0, 1)
        elif side == 1:
            x, y, direction = SIZE - 1, offset, (-1, 0)
        elif side == 2:
            x, y, direction = offset, SIZE - 1, (0, -1)
        else:
            x, y, direction = 0, offset, (1, 0)
        crack(cells, rng, x, y, direction, rng.randint(7, 12), 0)
    image = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    for (x, y), heat in cells.items():
        colour = BODY
        if heat == 2 and rng.random() < 0.45:
            colour = CORE
        image.putpixel((x, y), colour + (255,))
    # Dull embers beside the cracks: a few pixels, never a full outline.
    for (x, y) in list(cells):
        for dx, dy in STEPS:
            nx, ny = x + dx, y + dy
            if 0 <= nx < SIZE and 0 <= ny < SIZE and (nx, ny) not in cells and rng.random() < 0.12:
                image.putpixel((nx, ny), EDGE + (255,))
    return image


def build():
    rng = random.Random(SEED)
    return {f"ember_vein_{index}": vein(rng) for index in range(VARIANTS)}


def preview(sprites, path):
    scale = 8
    cell = SIZE * scale + 16
    sheet = Image.new("RGBA", (cell * len(sprites), cell * 2), (24, 26, 34, 255))
    plank = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 255))
    for y in range(SIZE):
        for x in range(SIZE):
            shade = 70 + (12 if (y // 4) % 2 else 0) + ((x * 7 + y * 3) % 5) * 3
            plank.putpixel((x, y), (shade + 22, shade, shade - 30, 255))
    for index, image in enumerate(sprites.values()):
        alone = image.resize((SIZE * scale, SIZE * scale), Image.NEAREST)
        sheet.alpha_composite(alone, (index * cell + 8, 8))
        lit = plank.copy()
        for y in range(SIZE):
            for x in range(SIZE):
                r, g, b, a = image.getpixel((x, y))
                if a:
                    pr, pg, pb, _ = lit.getpixel((x, y))
                    lit.putpixel((x, y), (min(255, pr + r), min(255, pg + g * 160 // 255), min(255, pb + b * 64 // 255), 255))
        sheet.alpha_composite(lit.resize((SIZE * scale, SIZE * scale), Image.NEAREST), (index * cell + 8, cell + 8))
    sheet.save(path)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--preview")
    options = parser.parse_args()
    sprites = build()
    OUTPUT.mkdir(parents=True, exist_ok=True)
    for name, image in sprites.items():
        image.save(OUTPUT / f"{name}.png")
    print(f"wrote {len(sprites)} sprites to {OUTPUT}")
    if options.preview:
        preview(sprites, options.preview)


if __name__ == "__main__":
    main()
