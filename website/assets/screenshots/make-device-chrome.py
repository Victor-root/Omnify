#!/usr/bin/env python3
"""Lift a phone's status bar, app bar and navigation bar off the screenshots.

The security section shows a card it draws in HTML inside the device frame (see
make-device-frame.py). The card has to stay HTML, or it would stop being
translated into the thirteen languages the page is in. The bars around it do
not: they are the same bars in every shot here, and copying them is exact where
redrawing them was a guess.

    python3 make-device-chrome.py    # <accent>-1.png -> device-chrome-<accent>.png
    python3 optimise.py              # -> .webp, like any other

One file per accent, since the bars carry the accent colour, and script.js swaps
them with the hero shots when a visitor picks a colour. Each is the full glass
with only those three bands kept and everything between them transparent, so it
can be laid over the card at the screen's own size with no offsets to keep in
step.

The app's own name is painted out of the app bar. The shots' name says "New apps",
which is not the page this card belongs to, and a real app's name there would read
as a claim about that app. The page writes its own stand-in name over the cleared
strip (see .device-title): it has to be text rather than pixels to say the same
thing in all thirteen languages the page is in. The back arrow stays: on its own
it already says "somewhere inside an app", which is the point of the frame.

Requires Pillow (pip install pillow).
"""

import os
import sys

try:
    from PIL import Image
except ImportError:
    sys.exit("Pillow is missing: pip install pillow")

ACCENTS = ("green", "red", "purple")

# The glass, in the source images. Same for every shot: see make-device-frame.py.
SCREEN = (60, 55, 1344, 2992)

# The status bar's own depth, the one thing here that can't be read off the
# colour: it and the app bar are the same colour and meet without a seam.
STATUS_HEIGHT = 168

# Everything right of the back arrow in the app bar, which is the page's name.
TITLE_FROM = 110

# How far a pixel may drift from the bar's colour and still count as the bar.
BAR_TOLERANCE = 26


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    sx, sy, sw, sh = SCREEN

    for accent in ACCENTS:
        source = Image.open(os.path.join(here, f"{accent}-1.png")).convert("RGBA")
        screen = source.crop((sx, sy, sx + sw, sy + sh))
        pixels = screen.load()

        # Read off the shot rather than hard-coded: each accent has its own, and
        # where that colour stops is what decides how much to clear.
        bar = pixels[sw // 2, STATUS_HEIGHT + 40]

        def is_bar(pixel):
            return all(abs(pixel[i] - bar[i]) < BAR_TOLERANCE for i in range(3))

        middle = sw // 2
        appbar_bottom = max(y for y in range(150, 700) if is_bar(pixels[middle, y])) + 1
        nav_top = min(y for y in range(sh - 1, sh - 400, -1) if is_bar(pixels[middle, y]))

        for y in range(STATUS_HEIGHT, appbar_bottom):
            for x in range(TITLE_FROM, sw):
                pixels[x, y] = bar

        for y in range(appbar_bottom, nav_top):
            for x in range(sw):
                pixels[x, y] = (0, 0, 0, 0)

        out = os.path.join(here, f"device-chrome-{accent}.png")
        screen.save(out)
        print(f"{accent}-1.png -> {os.path.basename(out)}: bandeau haut 0..{appbar_bottom}, "
              f"barre de navigation {nav_top}..{sh}")

    print("Run optimise.py next to produce the .webp files the page serves.")


if __name__ == "__main__":
    main()
