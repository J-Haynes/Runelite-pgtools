"""Makes icon.png: the artwork cropped to a circle around its visible content, filling the icon.

    python3 make-icon.py <artwork.jpg|png> icon.png [preview.png]

The circle is found from the artwork itself: centred on the bounding box of everything brighter
than the dark background, just big enough for (nearly) all of it - stray sparks are ignored.
"""
import math
import sys

from PIL import Image, ImageDraw

src, out = sys.argv[1:3]
preview = sys.argv[3] if len(sys.argv) > 3 else None

SIZE = 256
SS = 4                  # supersampling for smooth edges
MARGIN = 0.015          # just enough that the antialiased rim isn't cut off
BRIGHT = 40             # luminance above the near-black background
RIM_COLOUR = (70, 110, 170)

art = Image.open(src).convert("RGB")
w, h = art.size

# Find the circle around the visible artwork (on a small copy - only needs to be approximate).
probe_scale = 256 / max(w, h)
probe = art.convert("L").resize((round(w * probe_scale), round(h * probe_scale)))
left, top, right, bottom = probe.point(lambda v: 255 if v > BRIGHT else 0).getbbox()
cx, cy = (left + right) / 2, (top + bottom) / 2
px = probe.load()
dists = sorted(math.hypot(x - cx, y - cy) for y in range(probe.height) for x in range(probe.width)
               if px[x, y] > BRIGHT)
radius = dists[int(len(dists) * 0.999) - 1] * 1.02          # 99.9% of it, plus a little room
cx, cy, radius = cx / probe_scale, cy / probe_scale, radius / probe_scale

# Crop that circle's square (padding with black if it pokes outside the image).
box = (round(cx - radius), round(cy - radius), round(cx + radius), round(cy + radius))
square = Image.new("RGB", (box[2] - box[0], box[3] - box[1]), (0, 0, 0))
square.paste(art.crop((max(box[0], 0), max(box[1], 0), min(box[2], w), min(box[3], h))),
             (max(-box[0], 0), max(-box[1], 0)))

size = SIZE * SS
inset = round(size * MARGIN)
disc = (inset, inset, size - inset - 1, size - inset - 1)
canvas = Image.new("RGB", (size, size), (0, 0, 0))
canvas.paste(square.resize((size - 2 * inset, size - 2 * inset), Image.LANCZOS), (inset, inset))
mask = Image.new("L", (size, size), 0)
ImageDraw.Draw(mask).ellipse(disc, fill=255)
# Faint rim so the black disc doesn't vanish into a dark dock.
rim = Image.new("L", (size, size), 0)
ImageDraw.Draw(rim).ellipse(disc, outline=150, width=3 * SS)
canvas = Image.composite(Image.new("RGB", (size, size), RIM_COLOUR), canvas, rim)
icon = canvas.convert("RGBA")
icon.putalpha(mask)
icon = icon.resize((SIZE, SIZE), Image.LANCZOS)
icon.save(out)
print(f"circle centre ({cx:.0f}, {cy:.0f}), radius {radius:.0f}px of {w}x{h}")

if preview:
    prev = Image.new("RGBA", (560, 280))
    for i, bg in enumerate([(240, 240, 240, 255), (40, 40, 40, 255)]):
        tile = Image.new("RGBA", (280, 280), bg)
        tile.alpha_composite(icon, (12, 12))
        prev.paste(tile, (280 * i, 0))
    small = icon.resize((48, 48), Image.LANCZOS)
    prev.alpha_composite(small, (220, 220))
    prev.alpha_composite(small, (500, 220))
    prev.save(preview)
