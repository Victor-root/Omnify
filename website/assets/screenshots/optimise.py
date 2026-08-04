#!/usr/bin/env python3
"""Turn the exported hero screenshots into the files the site actually serves.

Export from the mockup tool as PNG with a transparent background, drop them in
this folder as <accent-id>-1.png .. -3.png, then run:

    python3 optimise.py

Each PNG becomes a .webp beside it, resized and compressed. The originals are
left alone: they are the source, the .webp files are the build output and the
only ones accents.js points at. A 1472px-wide export weighs about 2.3MB, which
is roughly twenty times more than this page ever needs.

Requires Pillow (pip install pillow).
"""

import glob
import os
import sys

try:
    from PIL import Image
except ImportError:
    sys.exit("Pillow is missing: pip install pillow")

# The widest the site ever draws each kind of screenshot, with room to spare on
# a high-density display. Nothing is upscaled past its source, so exporting
# larger than this costs disk in the repository and nothing on the page.
PHONE_WIDTH = 760   # the hero phones, drawn at up to 254 CSS pixels
TV_WIDTH = 2000     # the Android TV shot, drawn at up to 1000 CSS pixels
QUALITY = 90

here = os.path.dirname(os.path.abspath(__file__))
sources = sorted(glob.glob(os.path.join(here, "*.png")))

if not sources:
    sys.exit("No .png found next to this script.")

for path in sources:
    name = os.path.basename(path)
    image = Image.open(path).convert("RGBA")

    target = TV_WIDTH if name.startswith("tv-") else PHONE_WIDTH
    width = min(target, image.width)
    if width == image.width:
        resized = image
    else:
        height = round(image.height * width / image.width)
        resized = image.resize((width, height), Image.LANCZOS)

    out = os.path.splitext(path)[0] + ".webp"
    resized.save(out, "WEBP", quality=QUALITY, method=6)

    before = os.path.getsize(path) / 1024
    after = os.path.getsize(out) / 1024
    print(
        f"{name:16} {image.width}x{image.height} {before:7.0f} KB"
        f"  ->  {os.path.basename(out):17} {resized.width}x{resized.height} {after:6.0f} KB"
    )
