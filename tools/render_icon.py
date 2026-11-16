"""Render LiteLights' icon: a vanilla oak tree, seen from above at an angle, with a soft drop shadow.

Usage: python3 -I tools/render_icon.py [--jar <26.3 client jar>] [--preview <folder>] [--seed <n>]

The oak log, log top and leaf textures are read from the real Minecraft 26.3 client jar and copied
into tools/textures/ (git-ignored). The leaves are tinted with vanilla's default foliage colour.

How it matches the game:
- The tree is grown with vanilla's oak rules (a straight trunk, then blob foliage of radius 2 and
  height 3), using the same order of random corner choices; a fixed seed makes it reproducible.
- Leaves are drawn like Fancy leaves: cutout texels are see-through, and faces between neighbouring
  leaf blocks are kept, so the inside of the canopy shows through the gaps.
- Faces get Minecraft's fixed shading: top 100 %, north and south sides 80 %, east and west 60 %,
  times smooth-lighting ambient occlusion worked out per corner as the game does it (each full
  block next to a corner counts 0.2, open air 1.0, averaged over the four blocks around it).
- A depth buffer decides what is in front for every pixel, so no face shows through the wrong way.

Writes src/main/resources/assets/litelights/icon.png (512 px) and icon-128.png. With --preview it
also writes the icon at 512, 128, 64 and 32 px side by side on a dark and a light background.
"""
from pathlib import Path
import argparse, math, random, zipfile
import numpy as np
from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parent.parent
TEXTURES = ROOT / "tools" / "textures"
OUTPUT = ROOT / "src" / "main" / "resources" / "assets" / "litelights"
DEFAULT_JAR = Path.home() / ".local/share/ModrinthApp/meta/versions/26.3/26.3.jar"
NEEDED = ["oak_log.png", "oak_log_top.png", "oak_leaves.png"]

FOLIAGE_DEFAULT = (0x48, 0xB5, 0x18)  # vanilla's foliage colour where no biome applies
SIZE = 512
SUPERSAMPLE = 4

# The seed picks one draw of vanilla's random corners. This one leaves out the lowest layer's
# front corner, as many real oaks grow, so the trunk shows under the canopy from above.
SEED = 9
# Vanilla oaks are 4 to 6 logs tall; with 5, two bare logs stand below the leaves.
TRUNK_HEIGHT = 5

# The camera: looking north-west from the south-east, 30 degrees down, like a player looking at a tree.
YAW_DEGREES = 45.0
PITCH_DEGREES = 30.0

# Minecraft's fixed face shading.
SHADE = {"up": 1.0, "down": 0.5, "north": 0.8, "south": 0.8, "west": 0.6, "east": 0.6}

# Sunlight travels down and towards the north-east, so the shadow falls behind and to the right,
# away from the brighter south face.
SUN = np.array([0.35, -1.0, -0.25])
SHADOW_ALPHA = 0.38


def extract_textures(jar):
    TEXTURES.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(jar) as archive:
        for name in NEEDED:
            (TEXTURES / name).write_bytes(archive.read("assets/minecraft/textures/block/" + name))


def load(name, tint=None):
    image = np.asarray(Image.open(TEXTURES / name).convert("RGBA")).astype(np.float32) / 255.0
    image = image[:16, :16]  # animated textures are strips; the first frame is enough
    if tint is not None:
        image[..., :3] *= np.array(tint, dtype=np.float32) / 255.0
    return image


def grow_oak(seed):
    """
    (x, y, z, kind) for each block, y up, the log at x = z = 0. Follows vanilla's blob foliage
    placer: four leaf rows from the block above the top log downwards, radius 1, 1, 2, 2. A row's
    corner is left out when a coin flip says so, and always in the top row (which makes the plus).
    Leaves never replace the log.
    """
    rng = random.Random(seed)
    blocks = {(0, y, 0): "log" for y in range(TRUNK_HEIGHT)}
    attach = TRUNK_HEIGHT  # the foliage hangs from the block above the top log
    for local_y in range(0, -4, -1):
        # Java's integer division rounds towards zero, hence int(local_y / 2) rather than //.
        radius = max(2 - 1 - int(local_y / 2), 0)
        for x in range(-radius, radius + 1):
            for z in range(-radius, radius + 1):
                corner = abs(x) == radius and abs(z) == radius
                if corner and (rng.randrange(2) == 0 or local_y == 0):
                    continue
                position = (x, attach + local_y, z)
                blocks.setdefault(position, "leaves")
    return [(x, y, z, kind) for (x, y, z), kind in blocks.items()]


class Camera:
    """An orthographic camera: screen right, screen down, and depth towards the viewer."""

    def __init__(self, yaw, pitch):
        yaw = math.radians(yaw)
        pitch = math.radians(pitch)
        # Horizontal direction the viewer stands in, seen from the tree: south-east is +x +z.
        toward_viewer = np.array([math.sin(yaw), 0.0, math.cos(yaw)])
        right = np.array([math.cos(yaw), 0.0, -math.sin(yaw)])
        up = np.array([0.0, 1.0, 0.0])
        self.right = right
        self.down = -(up * math.cos(pitch) - toward_viewer * math.sin(pitch))
        self.depth = toward_viewer * math.cos(pitch) + up * math.sin(pitch)

    def screen(self, point):
        point = np.asarray(point, dtype=np.float64)
        return np.array([point @ self.right, point @ self.down])

    def depth_of(self, point):
        return float(np.asarray(point, dtype=np.float64) @ self.depth)


FACES = {
    # name: (normal, corner offset, u edge, v edge); u and v follow the texture's columns and rows.
    "up": ((0, 1, 0), (0, 1, 0), (1, 0, 0), (0, 0, 1)),
    "down": ((0, -1, 0), (0, 0, 1), (1, 0, 0), (0, 0, -1)),
    "north": ((0, 0, -1), (1, 1, 0), (-1, 0, 0), (0, -1, 0)),
    "south": ((0, 0, 1), (0, 1, 1), (1, 0, 0), (0, -1, 0)),
    "west": ((-1, 0, 0), (0, 1, 0), (0, 0, 1), (0, -1, 0)),
    "east": ((1, 0, 0), (1, 1, 1), (0, 0, -1), (0, -1, 0)),
}


class Raster:
    """A colour buffer with a depth buffer, in supersampled pixels, with a world-to-pixel mapping."""

    def __init__(self, size, camera, scale, offset):
        self.size = size
        self.camera = camera
        self.scale = scale
        self.offset = np.asarray(offset)
        self.colour = np.zeros((size, size, 4), dtype=np.float32)
        self.depth = np.full((size, size), -np.inf, dtype=np.float64)

    def pixel(self, point):
        return self.camera.screen(point) * self.scale + self.offset

    def draw_face(self, texture, corner, u_edge, v_edge, shade, occlusion=(1.0, 1.0, 1.0, 1.0)):
        """
        Maps the 16x16 texture onto the face, nearest texel. {@code occlusion} holds the corner
        brightness at (u, v) = (0, 0), (1, 0), (0, 1) and (1, 1), blended across the face.
        """
        corner = np.asarray(corner, dtype=np.float64)
        u_edge = np.asarray(u_edge, dtype=np.float64)
        v_edge = np.asarray(v_edge, dtype=np.float64)
        origin = self.pixel(corner)
        u_vector = self.pixel(corner + u_edge) - origin
        v_vector = self.pixel(corner + v_edge) - origin
        matrix = np.column_stack([u_vector, v_vector])
        if abs(np.linalg.det(matrix)) < 1e-6:
            return
        inverse = np.linalg.inv(matrix)
        corners = [origin, origin + u_vector, origin + v_vector, origin + u_vector + v_vector]
        left = max(0, int(math.floor(min(c[0] for c in corners))))
        right = min(self.size, int(math.ceil(max(c[0] for c in corners))))
        top = max(0, int(math.floor(min(c[1] for c in corners))))
        bottom = min(self.size, int(math.ceil(max(c[1] for c in corners))))
        if right <= left or bottom <= top:
            return
        ys, xs = np.mgrid[top:bottom, left:right]
        offsets = np.stack([xs + 0.5 - origin[0], ys + 0.5 - origin[1]], axis=-1)
        uv = offsets @ inverse.T
        inside = (uv[..., 0] >= 0) & (uv[..., 0] < 1) & (uv[..., 1] >= 0) & (uv[..., 1] < 1)
        texels = texture[np.clip((uv[..., 1] * 16).astype(int), 0, 15), np.clip((uv[..., 0] * 16).astype(int), 0, 15)]
        depth = (self.camera.depth_of(corner) + uv[..., 0] * self.camera.depth_of(u_edge)
                 + uv[..., 1] * self.camera.depth_of(v_edge))
        region_depth = self.depth[top:bottom, left:right]
        # Cutout: transparent texels are skipped entirely, as with Minecraft's cutout leaves.
        drawn = inside & (texels[..., 3] > 0.5) & (depth > region_depth)
        region = self.colour[top:bottom, left:right]
        u = np.clip(uv[..., 0], 0, 1)
        v = np.clip(uv[..., 1], 0, 1)
        a00, a10, a01, a11 = occlusion
        light = shade * ((1 - u) * (1 - v) * a00 + u * (1 - v) * a10 + (1 - u) * v * a01 + u * v * a11)
        region[drawn, :3] = texels[drawn, :3] * light[drawn][:, None]
        region[drawn, 3] = 1.0
        region_depth[drawn] = depth[drawn]


def render_tree(raster, blocks, textures):
    """
    Draws every face turned towards the camera. Faces against a log are hidden, since logs are
    opaque; faces between leaf blocks stay, as Fancy leaves draw them.
    """
    logs = {(x, y, z) for x, y, z, kind in blocks if kind == "log"}
    occupied = {(x, y, z) for x, y, z, _ in blocks}
    for x, y, z, kind in blocks:
        for name, (normal, corner_offset, u_edge, v_edge) in FACES.items():
            if raster.camera.depth_of(normal) <= 0:
                continue  # back faces are culled, as in the game
            if (x + normal[0], y + normal[1], z + normal[2]) in logs:
                continue
            if kind == "log":
                texture = textures["log_top"] if name in ("up", "down") else textures["log"]
            else:
                texture = textures["leaves"]
            corner = (x + corner_offset[0], y + corner_offset[1], z + corner_offset[2])
            occlusion = corner_occlusion(occupied, (x, y, z), normal, u_edge, v_edge)
            raster.draw_face(texture, corner, u_edge, v_edge, SHADE[name], occlusion)


def corner_occlusion(occupied, block, normal, u_edge, v_edge):
    """
    Smooth-lighting brightness at the face's four corners, as Minecraft's ambient occlusion works
    it out: the block in front of the face and the three around each corner, each 0.2 when it is
    a full block (logs and leaves both are) and 1.0 when open. When both side blocks are full, the
    diagonal one can't be seen, so a side is counted in its place.
    """
    def brightness(position):
        return 0.2 if position in occupied else 1.0

    front = np.add(block, normal)
    values = []
    for v_sign in (-1, 1):
        for u_sign in (-1, 1):
            side_u = tuple(front + np.multiply(u_edge, u_sign))
            side_v = tuple(front + np.multiply(v_edge, v_sign))
            diagonal = tuple(front + np.multiply(u_edge, u_sign) + np.multiply(v_edge, v_sign))
            both_sides = side_u in occupied and side_v in occupied
            corner_value = brightness(side_u) if both_sides else brightness(diagonal)
            values.append((brightness(tuple(front)) + brightness(side_u) + brightness(side_v) + corner_value) / 4)
    # Ordered (u, v) = (0, 0), (1, 0), (0, 1), (1, 1).
    return tuple(values)


def shadow_polygons(raster, blocks):
    """Each block's shadow on the ground (y = 0) along the sun, as a polygon in pixels."""
    polygons = []
    for x, y, z, kind in blocks:
        points = []
        for cx in (x, x + 1):
            for cy in (y, y + 1):
                for cz in (z, z + 1):
                    travel = cy / -SUN[1]
                    ground = (cx + SUN[0] * travel, 0.0, cz + SUN[2] * travel)
                    points.append(tuple(raster.pixel(ground)))
        polygons.append(convex_hull(points))
    return polygons


def convex_hull(points):
    points = sorted(set(points))
    if len(points) <= 2:
        return points

    def cross(o, a, b):
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])

    lower, upper = [], []
    for point in points:
        while len(lower) >= 2 and cross(lower[-2], lower[-1], point) <= 0:
            lower.pop()
        lower.append(point)
    for point in reversed(points):
        while len(upper) >= 2 and cross(upper[-2], upper[-1], point) <= 0:
            upper.pop()
        upper.append(point)
    return lower[:-1] + upper[:-1]


def fit(blocks, camera, size, margin):
    """Scale and offset that fit the tree and its shadow in the square, centred."""
    unit = Raster(1, camera, 1.0, (0.0, 0.0))
    points = []
    for x, y, z, _ in blocks:
        for cx in (x, x + 1):
            for cy in (y, y + 1):
                for cz in (z, z + 1):
                    points.append(unit.pixel((cx, cy, cz)))
    for polygon in shadow_polygons(unit, blocks):
        points.extend(np.array(point) for point in polygon)
    points = np.array(points)
    low, high = points.min(axis=0), points.max(axis=0)
    scale = size * (1 - 2 * margin) / max(high - low)
    centre = (low + high) / 2 * scale
    return scale, (size / 2 - centre[0], size / 2 - centre[1])


def render(size, seed):
    big = size * SUPERSAMPLE
    textures = {"log": load("oak_log.png"), "log_top": load("oak_log_top.png"),
                "leaves": load("oak_leaves.png", FOLIAGE_DEFAULT)}
    blocks = grow_oak(seed)
    camera = Camera(YAW_DEGREES, PITCH_DEGREES)
    scale, offset = fit(blocks, camera, big, 0.04)
    raster = Raster(big, camera, scale, offset)

    mask = Image.new("L", (big, big), 0)
    draw = ImageDraw.Draw(mask)
    for polygon in shadow_polygons(raster, blocks):
        draw.polygon(polygon, fill=255)
    mask = mask.filter(ImageFilter.GaussianBlur(big * 0.012))
    shadow = np.asarray(mask, dtype=np.float32) / 255.0 * SHADOW_ALPHA

    render_tree(raster, blocks, textures)
    tree = raster.colour
    # The tree over its shadow; the shadow is black at partial opacity on a transparent ground.
    alpha = tree[..., 3] + shadow * (1 - tree[..., 3])
    rgb = tree[..., :3] * tree[..., 3:4]
    out = np.dstack([np.divide(rgb, alpha[..., None], out=np.zeros_like(rgb), where=alpha[..., None] > 0), alpha])
    image = Image.fromarray((np.clip(out, 0, 1) * 255).astype(np.uint8), "RGBA")
    return image.resize((size, size), Image.Resampling.BOX)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--jar", type=Path, default=DEFAULT_JAR)
    parser.add_argument("--preview", type=Path)
    parser.add_argument("--seed", type=int, default=SEED)
    args = parser.parse_args()
    extract_textures(args.jar)
    icon = render(SIZE, args.seed)
    if args.seed == SEED:
        OUTPUT.mkdir(parents=True, exist_ok=True)
        icon.save(OUTPUT / "icon.png", optimize=True)
        icon.resize((128, 128), Image.Resampling.LANCZOS).save(OUTPUT / "icon-128.png", optimize=True)
    if args.preview:
        args.preview.mkdir(parents=True, exist_ok=True)
        for backdrop_name, backdrop in (("dark", (40, 40, 40, 255)), ("light", (225, 225, 225, 255))):
            sheet = Image.new("RGBA", (512 + 3 * 256 + 40, 512), backdrop)
            sheet.alpha_composite(icon, (0, 0))
            x = 522
            for small in (128, 64, 32):
                scaled = icon.resize((small, small), Image.Resampling.LANCZOS).resize((256, 256), Image.Resampling.NEAREST)
                sheet.alpha_composite(scaled, (x, 128))
                x += 266
            sheet.save(args.preview / f"icon-seed{args.seed}-{backdrop_name}.png")


if __name__ == "__main__":
    main()
