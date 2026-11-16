"""Draw LiteLights' particle sprites: small pixel art at vanilla's particle scale.

Usage: python3 -I tools/render_particle_sprites.py [--preview <file.png>]

Every sprite is white or grey on transparent, so one sprite serves many colours: the particle's
own colour tints it in the game. Small sprites are drawn from the character maps below; the soft
ones (mist, smoke, dust clouds) are built from a few hard alpha steps and an ordered dither,
so they stay crisp pixel art instead of smooth gradients. A fixed seed keeps the output the same
on every run.

Writes src/main/resources/assets/litelights/textures/particle/<name>.png. The particle atlas loads
every texture under textures/particle/, so no atlas file is needed. With --preview it also writes
a sheet of every sprite at 8x on a dark background.
"""
from pathlib import Path
import argparse
import math
import random
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
OUTPUT = ROOT / "src" / "main" / "resources" / "assets" / "litelights" / "textures" / "particle"
SEED = 2611

# Character maps: '.' is transparent, digits are alpha steps (9 is opaque), letters are greys at
# full alpha (A is white, B light grey, C mid grey).
GREYS = {"A": 255, "B": 214, "C": 170}

SMALL = {
    # Rain: a thin streak, brightest at the leading end. Drawn stretched along the drop's motion.
    "rain_drop": [
        "2",
        "3",
        "3",
        "4",
        "5",
        "5",
        "6",
        "6",
        "7",
        "7",
        "8",
        "8",
        "9",
        "A",
        "A",
        "9",
    ],
    # Rain hitting the ground: a crown that opens and falls back.
    "rain_splash_0": [
        "........",
        "........",
        "........",
        "........",
        "........",
        "...77...",
        "..7AA7..",
        "........",
    ],
    "rain_splash_1": [
        "........",
        "........",
        "........",
        "..6..6..",
        "...66...",
        ".7....7.",
        ".7A..A7.",
        "........",
    ],
    "rain_splash_2": [
        "........",
        "..5..5..",
        ".5....5.",
        "........",
        "5......5",
        "6......6",
        "........",
        "........",
    ],
    "rain_splash_3": [
        ".3....3.",
        "........",
        "3......3",
        "........",
        "........",
        "........",
        "........",
        "........",
    ],
    # Snow, vanilla style: chunky flakes of two to five pixels, like vanilla's snowflake particle.
    "snow_v_0": [
        "........",
        "........",
        "...AA...",
        "..AAAA..",
        "..AAAA..",
        "...AA...",
        "........",
        "........",
    ],
    "snow_v_1": [
        "........",
        "........",
        "........",
        "...AB...",
        "...BA...",
        "........",
        "........",
        "........",
    ],
    "snow_v_2": [
        "........",
        "........",
        "...A....",
        "..ABA...",
        "...A....",
        "........",
        "........",
        "........",
    ],
    "snow_v_3": [
        "........",
        "........",
        "..A.....",
        "...AB...",
        "....A...",
        "........",
        "........",
        "........",
    ],
    # Snow, HD: little six-armed crystals, finer pixels at the same size on screen.
    "snow_hd_0": [
        "................",
        "................",
        ".......A........",
        "....6..A..6.....",
        ".....A.A.A......",
        "......AAA.......",
        "...AAAAAAAAA....",
        "......AAA.......",
        ".....A.A.A......",
        "....6..A..6.....",
        ".......A........",
        "................",
        "................",
        "................",
        "................",
        "................",
    ],
    "snow_hd_1": [
        "................",
        "................",
        "................",
        ".......5........",
        "......5A5.......",
        "....5.AAA.5.....",
        "....AAABAAA.....",
        "....5.AAA.5.....",
        "......5A5.......",
        ".......5........",
        "................",
        "................",
        "................",
        "................",
        "................",
        "................",
    ],
    "snow_hd_2": [
        "................",
        "................",
        "................",
        "................",
        "......7.7.......",
        ".......A........",
        ".....7AAA7......",
        ".......A........",
        "......7.7.......",
        "................",
        "................",
        "................",
        "................",
        "................",
        "................",
        "................",
    ],
    "snow_hd_3": [
        "................",
        "................",
        "......A...A.....",
        ".......A.A......",
        "....A..BAB..A...",
        ".....A.AAA.A....",
        "......AAAAA.....",
        "...AAAAAAAAAAA..",
        "......AAAAA.....",
        ".....A.AAA.A....",
        "....A..BAB..A...",
        ".......A.A......",
        "......A...A.....",
        "................",
        "................",
        "................",
    ],
    # Sand, vanilla style: one to three grains.
    "sand_v_0": [
        "........",
        "........",
        "........",
        "...AB...",
        "...B....",
        "........",
        "........",
        "........",
    ],
    "sand_v_1": [
        "........",
        "........",
        "....A...",
        "........",
        "..B.....",
        "........",
        "........",
        "........",
    ],
    "sand_v_2": [
        "........",
        "........",
        "........",
        "...BA...",
        "...AC...",
        "........",
        "........",
        "........",
    ],
    # Sand, HD: a scatter of fine grains in three shades.
    "sand_hd_0": [
        "................",
        "................",
        "................",
        "......A.........",
        "..........B.....",
        "....C...........",
        ".......AB.......",
        ".......B........",
        "...........A....",
        "....B...........",
        "........C.......",
        "................",
        "................",
        "................",
        "................",
        "................",
    ],
    "sand_hd_1": [
        "................",
        "................",
        "................",
        "................",
        ".....B....A.....",
        "................",
        "........A.......",
        "......CAB.......",
        "........B.......",
        "...A........C...",
        "................",
        "................",
        "................",
        "................",
        "................",
        "................",
    ],
    "sand_hd_2": [
        "................",
        "................",
        "................",
        "................",
        "................",
        "......B.........",
        "........AA......",
        ".....C..AB...B..",
        "................",
        "..........C.....",
        "................",
        "................",
        "................",
        "................",
        "................",
        "................",
    ],
    # Fireflies: a bright body with a soft plus of light; the second frame is the flash.
    "firefly_0": [
        "........",
        "........",
        "...33...",
        "..3AA3..",
        "..3AA3..",
        "...33...",
        "........",
        "........",
    ],
    "firefly_1": [
        "........",
        "...22...",
        "..4664..",
        ".26AA62.",
        ".26AA62.",
        "..4664..",
        "...22...",
        "........",
    ],
    # Dust specks and motes.
    "speck": [
        "....",
        ".A..",
        "....",
        "....",
    ],
    "mote": [
        ".....",
        "..5..",
        ".5A5.",
        "..5..",
        ".....",
    ],
    "pollen_0": [
        "......",
        "..A...",
        ".ABA..",
        "..A...",
        "......",
        "......",
    ],
    "pollen_1": [
        "......",
        "......",
        "..AB..",
        "..BA..",
        "......",
        "......",
    ],
    "spore_0": [
        "......",
        "..66..",
        ".6AA6.",
        ".6AA6.",
        "..66..",
        "......",
    ],
    "spore_1": [
        "......",
        "......",
        "..A...",
        "...A..",
        "......",
        "......",
    ],
    "spore_2": [
        "......",
        "...5..",
        "..5A5.",
        "...5..",
        "......",
        "......",
    ],
    # Embers: a hot spark with a short trail below it.
    "ember_0": [
        "......",
        "..A...",
        "..A...",
        "..6...",
        "..3...",
        "......",
    ],
    "ember_1": [
        "......",
        "..AA..",
        "..AA..",
        "..5...",
        "......",
        "......",
    ],
    "ember_2": [
        "......",
        "...A..",
        "..A...",
        "..4...",
        "......",
        "......",
    ],
    # Glowing drips (crimson forests): a bead hanging under a thin neck.
    "drip": [
        "..4...",
        "..6...",
        "..8...",
        ".8A8..",
        ".AAA..",
        "..A...",
    ],
    "ash_0": [
        "......",
        "......",
        "..AB..",
        "...CB.",
        "......",
        "......",
    ],
    "ash_1": [
        "......",
        "..B...",
        "..AA..",
        "......",
        "......",
        "......",
    ],
}


def bayer(x, y):
    """4x4 ordered dither threshold, 0..1."""
    matrix = [[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]]
    return (matrix[y % 4][x % 4] + 0.5) / 16.0


def from_map(rows):
    height, width = len(rows), len(rows[0])
    image = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    for y, row in enumerate(rows):
        assert len(row) == width, rows
        for x, cell in enumerate(row):
            if cell == ".":
                continue
            if cell in GREYS:
                grey = GREYS[cell]
                image.putpixel((x, y), (grey, grey, grey, 255))
            else:
                alpha = round(int(cell) / 9 * 255)
                image.putpixel((x, y), (255, 255, 255, alpha))
    return image


def soft_blob(size, lumps, alpha_steps, rng, edge_noise=0.18, grey=255):
    """A cloud built from overlapping discs, cut into a few alpha steps with a dither between them."""
    centre = (size - 1) / 2
    discs = []
    for _ in range(lumps):
        angle = rng.uniform(0, math.tau)
        reach = rng.uniform(0.0, size * 0.18)
        radius = rng.uniform(size * 0.22, size * 0.34)
        discs.append((centre + math.cos(angle) * reach, centre + math.sin(angle) * reach, radius))
    image = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    for y in range(size):
        for x in range(size):
            density = 0.0
            for cx, cy, radius in discs:
                d = math.hypot(x - cx, y - cy) / radius
                density = max(density, 1.0 - d)
            density += rng.uniform(-edge_noise, edge_noise) * min(1.0, density * 4)
            if density <= 0:
                continue
            # Quantise into steps; the fraction between two steps decides by dither which one shows.
            scaled = min(density * 1.6, 0.999) * len(alpha_steps)
            step = int(scaled)
            if scaled - step > bayer(x, y) and step + 1 < len(alpha_steps):
                step += 1
            alpha = alpha_steps[step]
            if alpha > 0:
                image.putpixel((x, y), (grey, grey, grey, alpha))
    return image


def smooth_haze(size, lumps, rng):
    """A cloud of overlapping soft discs with a smooth edge that reaches zero well inside the square."""
    centre = (size - 1) / 2
    discs = []
    for _ in range(lumps):
        angle = rng.uniform(0, math.tau)
        reach = rng.uniform(0.0, size * 0.14)
        radius = rng.uniform(size * 0.2, size * 0.3)
        discs.append((centre + math.cos(angle) * reach, centre + math.sin(angle) * reach, radius))
    image = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    for y in range(size):
        for x in range(size):
            density = 0.0
            for cx, cy, radius in discs:
                d = math.hypot(x - cx, y - cy) / radius
                if d < 1:
                    density += (1 - d * d) ** 2
            # Never reach the square's edge, whatever the lumps did.
            edge = math.hypot(x - centre, y - centre) / (size / 2)
            density *= max(0.0, 1.0 - edge ** 3)
            alpha = int(255 * min(1.0, density * 0.75))
            if alpha > 0:
                image.putpixel((x, y), (255, 255, 255, alpha))
    return image


def ripple(size, radius, alpha):
    """A one-pixel ring lying flat on the water, broken up a little so it reads as drawn."""
    centre = (size - 1) / 2
    image = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    for y in range(size):
        for x in range(size):
            d = math.hypot(x - centre, y - centre)
            if abs(d - radius) < 0.55:
                image.putpixel((x, y), (255, 255, 255, alpha))
    return image


def scorch(size, rng):
    """A burn mark: a ragged dark patch, darkest in the middle, with a few loose cinders."""
    centre = (size - 1) / 2
    image = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    wobble = [rng.uniform(0.75, 1.0) for _ in range(12)]
    for y in range(size):
        for x in range(size):
            angle = math.atan2(y - centre, x - centre)
            sector = (angle + math.pi) / math.tau * 12
            low = wobble[int(sector) % 12]
            high = wobble[(int(sector) + 1) % 12]
            edge = low + (high - low) * (sector - int(sector))
            d = math.hypot(x - centre, y - centre) / (size / 2) / edge
            if d >= 1:
                if rng.random() < 0.03:
                    image.putpixel((x, y), (40, 36, 32, 150))
                continue
            alpha = 230 if d < 0.45 else 190 if d < 0.75 else 120
            if d > 0.75 and bayer(x, y) > 0.6:
                continue
            shade = 22 if d < 0.45 else 34
            image.putpixel((x, y), (shade, shade - 2, shade - 4, alpha))
    return image


def wisp(size, phase, rng):
    """A curling thread of light, as the soul sand valley's wisps."""
    image = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    steps = size * 3
    for i in range(steps):
        t = i / steps
        x = size / 2 + math.sin(t * 5.0 + phase) * size * 0.22 * (1 - t * 0.5)
        y = size - 1 - t * (size - 2)
        alpha = int(255 * (1 - t) ** 0.7)
        px, py = int(round(x)), int(round(y))
        if 0 <= px < size and 0 <= py < size:
            current = image.getpixel((px, py))[3]
            image.putpixel((px, py), (255, 255, 255, max(current, alpha)))
            if t < 0.25 and px + 1 < size:
                image.putpixel((px + 1, py), (255, 255, 255, max(image.getpixel((px + 1, py))[3], alpha // 2)))
    return image


def build():
    rng = random.Random(SEED)
    sprites = {name: from_map(rows) for name, rows in SMALL.items()}
    for frame in range(4):
        radius = 1.5 + frame * 1.6
        sprites[f"ripple_{frame}"] = ripple(16, radius, [230, 190, 140, 80][frame])
    # Haze for mist, dust clouds, ash haze and End mist: smooth falloff all the way to the edge, drawn
    # by LiteLights' soft particle shader so the faint rim survives and puffs never read as squares.
    for index in range(3):
        sprites[f"haze_{index}"] = smooth_haze(32, 4 + index, rng)
    for index in range(4):
        sprites[f"smoke_{index}"] = soft_blob(16, 3 + index % 2, [0, 90, 150, 200], rng, grey=235)
    for index in range(2):
        sprites[f"scorch_{index}"] = scorch(16, rng)
    for index in range(4):
        sprites[f"wisp_{index}"] = wisp(16, index * 1.4, rng)
    return sprites


# Sprites other tools own in the same folder: draw_water_particles.py and draw_ember_veins.py.
OTHER_TOOL_PREFIXES = ("splash_", "water_", "ember_vein_")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--preview")
    options = parser.parse_args()
    sprites = build()
    OUTPUT.mkdir(parents=True, exist_ok=True)
    # The folder is shared: other tools own the splash, water and ember vein sprites there.
    # Only this tool's own leftovers (sprites it no longer draws) are removed.
    for old in OUTPUT.glob("*.png"):
        if old.stem not in sprites and not old.stem.startswith(OTHER_TOOL_PREFIXES):
            old.unlink()
    for name, image in sprites.items():
        image.save(OUTPUT / f"{name}.png")
    print(f"wrote {len(sprites)} sprites to {OUTPUT}")
    if options.preview:
        scale, cell = 8, 34 * 8
        columns = 8
        rows = math.ceil(len(sprites) / columns)
        sheet = Image.new("RGBA", (columns * cell, rows * cell), (24, 26, 34, 255))
        for index, image in enumerate(sprites.values()):
            big = image.resize((image.width * scale, image.height * scale), Image.NEAREST)
            x = (index % columns) * cell + (cell - big.width) // 2
            y = (index // columns) * cell + (cell - big.height) // 2
            sheet.alpha_composite(big, (x, y))
        sheet.save(options.preview)


if __name__ == "__main__":
    main()
