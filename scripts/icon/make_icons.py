"""Draws the plugin's two clover icons (T-060). Needs Pillow. Run from the repo root:

    python3 scripts/icon/make_icons.py

- src/main/resources/com/pvmlogs/panel_icon.png: the side panel's button. RuneLite shrinks it to 16x16 (1.13.4's
  ClientUI), so it is drawn at exactly 16x16, pixel by pixel, to stay crisp.
- icon.png: the Plugin Hub listing's icon (the hub allows up to 48x72). Drawn large and scaled down to 48x48.
"""
import math
from PIL import Image, ImageChops, ImageDraw, ImageFilter

LEAF = (92, 190, 74)
LEAF_HI = (150, 222, 120)
EDGE = (20, 58, 26)
STEM = (70, 150, 56)

# ---- 16x16, by hand ----

# The top-left leaf: a heart whose point is its bottom-right corner, the clover's middle; the other three are its
# mirror images. 1 leaf, h highlight, . empty.
LEAF_ROWS = [
	"..11.",
	".h111",
	"1h111",
	"11111",
	".1111",
]
STEM_PIXELS = [(7, 11), (7, 12), (8, 13), (9, 14), (9, 15)]


def small(size=16, ox=2, oy=1, gap=1):
	n = len(LEAF_ROWS)
	cells = {}
	for qx in (0, 1):
		for qy in (0, 1):
			for y in range(n):
				for x in range(n):
					c = LEAF_ROWS[y][x]
					if c == ".":
						continue
					X = ox + (x if qx == 0 else n + gap + (n - 1 - x))
					Y = oy + (y if qy == 0 else n + gap + (n - 1 - y))
					cells[(X, Y)] = c
	mask = Image.new("L", (size, size), 0)
	for p in list(cells) + STEM_PIXELS:
		mask.putpixel(p, 255)
	outline = mask.filter(ImageFilter.MaxFilter(3))
	im = Image.new("RGBA", (size, size), (0, 0, 0, 0))
	for y in range(size):
		for x in range(size):
			if outline.getpixel((x, y)):
				im.putpixel((x, y), EDGE + (255,))
	for p in STEM_PIXELS:
		im.putpixel(p, STEM + (255,))
	for p, c in cells.items():
		im.putpixel(p, (LEAF_HI if c == "h" else LEAF) + (255,))
	return im


# ---- 48x48, drawn 16 times larger and scaled down ----

def large(size=48, ss=16, cy=0.43, d=0.25, r=0.098, p=0.068, tip=0.03, stem_w=0.075, highlight=0.45, outline=2):
	S = size * ss
	leaves = Image.new("L", (S, S), 0)
	shine = Image.new("L", (S, S), 0)
	stem = Image.new("L", (S, S), 0)
	dl, dh, ds = ImageDraw.Draw(leaves), ImageDraw.Draw(shine), ImageDraw.Draw(stem)
	cx, cy = S * 0.5, S * cy
	ds.line([(cx + S * 0.16 * (i / 20) ** 1.6, cy + S * 0.14 + S * 0.38 * (i / 20)) for i in range(21)],
		fill=255, width=int(S * stem_w), joint="curve")
	R = S * r
	for k in range(4):
		a = math.radians(45 + 90 * k)
		ux, uy = math.cos(a), math.sin(a)
		px, py = -uy, ux
		lobes = [(cx + ux * S * d + px * S * p * s, cy + uy * S * d + py * S * p * s) for s in (1, -1)]
		for x, y in lobes:
			dl.ellipse([x - R, y - R, x + R, y + R], fill=255)
		edges = [(x - ux * R * 0.55 + sgn * px * R * 0.80, y - uy * R * 0.55 + sgn * py * R * 0.80)
			for (x, y), sgn in zip(lobes, (1, -1))]
		dl.polygon([edges[0], lobes[0], lobes[1], edges[1], (cx + ux * S * tip, cy + uy * S * tip)], fill=255)
		hx = sum(l[0] for l in lobes) / 2 + ux * R * 0.15 - S * 0.03
		hy = sum(l[1] for l in lobes) / 2 + uy * R * 0.15 - S * 0.03
		hr = R * highlight
		dh.ellipse([hx - hr, hy - hr, hx + hr, hy + hr], fill=255)
	leaves, shine, stem = (m.resize((size, size), Image.LANCZOS) for m in (leaves, shine, stem))
	ring = leaves
	edge = ImageChops.lighter(leaves, stem)
	for _ in range(outline):
		ring = ring.filter(ImageFilter.MaxFilter(3))
		edge = edge.filter(ImageFilter.MaxFilter(3))
	im = Image.new("RGBA", (size, size), (0, 0, 0, 0))
	fill = lambda colour: Image.new("RGBA", (size, size), colour + (255,))
	im.paste(fill(EDGE), (0, 0), edge)
	im.paste(fill(STEM), (0, 0), ImageChops.subtract(stem, ring))  # the stem starts below the leaves
	im.paste(fill(LEAF), (0, 0), leaves)
	im.paste(fill(LEAF_HI), (0, 0), ImageChops.multiply(shine, leaves))
	return im


if __name__ == "__main__":
	small().save("src/main/resources/com/pvmlogs/panel_icon.png")
	large().save("icon.png")
