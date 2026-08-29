#!/usr/bin/env python3
"""Turn one of the hero screenshots into an empty device frame.

The security section draws a card in HTML and puts it inside a phone. The phone
has to be this same mockup, or the page would carry two different devices; and
the card has to stay HTML, or it would stop being translated into the thirteen
languages the rest of the page is in. So the frame is lifted off a screenshot
with its screen punched out, and the card is laid in behind it:

    python3 make-device-frame.py

Reads SOURCE, writes device-frame.png beside it, then optimise.py turns that
into the .webp the page serves like any other screenshot here.

What is kept and what is dropped:

  * the metal edge and the black bezel, which are the frame itself
  * the front camera, which sits on the screen rather than beside it and would
    leave a hole in the glass if it went with the rest
  * everything else inside the glass goes transparent, the status bar and the
    navigation bar included: both are drawn in the accent colour of whichever
    set the shot came from, and the page lets a visitor change that colour.

The screen is found row by row rather than as a fixed rectangle, so the corners
come out following the bezel's own curve instead of a radius guessed to match.

Requires Pillow (pip install pillow).
"""

import os
import sys

try:
    from PIL import Image
except ImportError:
    sys.exit("Pillow is missing: pip install pillow")

SOURCE = "green-2.png"
OUTPUT = "device-frame.png"

# The bezel is the only long run of near-black across a row. A shorter one is a
# seam in the metal edge, which is why a run has to earn its place by length.
BEZEL_DARK = 34
BEZEL_MIN_RUN = 12

# The camera, measured off the source rather than assumed: a disc centred on the
# screen's own midline. Restored whole to FEATHER_FROM, then faded out, so its
# antialiased rim doesn't come back as a hard circle.
CAMERA_CENTRE = (731.5, 155.5)
FEATHER_FROM = 45.0
FEATHER_TO = 49.0


def dark(pixel):
    return pixel[0] < BEZEL_DARK and pixel[1] < BEZEL_DARK and pixel[2] < BEZEL_DARK


def bezel_runs(line):
    """Every run of near-black at least BEZEL_MIN_RUN long, as (start, end).

    Runs touching either end of the line are dropped. Across the curved top and
    bottom of the phone the metal edge turns dark too, and that shadow reads as a
    run of its own, sitting outside the bezel and running off the image. Taking
    it for the bezel cleared the bezel along with the screen, which showed as the
    top of the frame going missing.
    """
    runs, start = [], None
    for i, pixel in enumerate(line):
        if dark(pixel):
            if start is None:
                start = i
        else:
            if start is not None and i - start >= BEZEL_MIN_RUN:
                runs.append((start, i - 1))
            start = None
    if start is not None and len(line) - start >= BEZEL_MIN_RUN:
        runs.append((start, len(line) - 1))
    return [r for r in runs if r[0] > 1 and r[1] < len(line) - 2]


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    image = Image.open(os.path.join(here, SOURCE)).convert("RGBA")
    width, height = image.size
    original = image.copy()
    pixels = image.load()
    source = original.load()

    # Where the glass starts and stops vertically, read down the middle of the
    # phone. Rows outside it also have a bezel run on either side, being the two
    # sides of the rounded top and bottom, and clearing between those would punch
    # the end off the frame rather than the screen out of it.
    column = bezel_runs([source[width // 2, y] for y in range(height)])
    screen_top, screen_bottom = column[0][1] + 1, column[-1][0] - 1

    cleared = 0
    for y in range(screen_top, screen_bottom + 1):
        runs = bezel_runs([source[x, y] for x in range(width)])
        if len(runs) < 2:
            continue
        for x in range(runs[0][1] + 1, runs[-1][0]):
            pixels[x, y] = (0, 0, 0, 0)
            cleared += 1

    cx, cy = CAMERA_CENTRE
    for y in range(int(cy - FEATHER_TO) - 1, int(cy + FEATHER_TO) + 2):
        for x in range(int(cx - FEATHER_TO) - 1, int(cx + FEATHER_TO) + 2):
            distance = ((x - cx) ** 2 + (y - cy) ** 2) ** 0.5
            if distance > FEATHER_TO:
                continue
            r, g, b, a = source[x, y]
            if distance > FEATHER_FROM:
                fade = (FEATHER_TO - distance) / (FEATHER_TO - FEATHER_FROM)
                a = round(a * fade)
            pixels[x, y] = (r, g, b, a)

    out = os.path.join(here, OUTPUT)
    image.save(out)
    print(f"{SOURCE} -> {OUTPUT}: {cleared} pixels d'ecran effaces, appareil photo conserve")
    print("Run optimise.py next to produce the .webp the page serves.")


if __name__ == "__main__":
    main()
