# Screenshots

Two sets live here: the accent-colour ones behind the header's colour picker,
and the gallery ones further down the page, which come in a light and a dark
version each. Drop either kind here as PNG, then run `optimise.py`. The PNGs are
the source, the `.webp` files it produces are what the site serves.

    python3 optimise.py

A 1472px export weighs about 2.3MB; the same screenshot at the size this page
actually draws it is nearer 100KB. That is the whole reason for the step. The
script sizes each file from its name: `tv-` prefixed ones are kept large because
the page draws them across the full column, everything else is a hero phone.

## Naming: the accent sets

`accents.js` points at these by file name, so no code needs editing when a set
is added or replaced. Each colour needs three hero screenshots and one Android
TV screenshot:

```
<accent-id>-1.png
<accent-id>-2.png
<accent-id>-3.png
tv-<accent-id>.png
```

`<accent-id>` is the `id` of the matching entry in `../../accents.js`, so today:

```
green-1.png   green-2.png   green-3.png   tv-green.png
red-1.png     red-2.png     red-3.png     tv-red.png
purple-1.png  purple-2.png  purple-3.png  tv-purple.png
```

Adding a fourth colour means adding its entry to `accents.js`, a label to both
locales in `i18n.js`, and four files named after its id.

## What the numbers mean

The three hero shots are stacked, not shown side by side, so the order decides
the layout:

- `-1` sits on the **left**, tilted back, partly behind the middle one
- `-2` is the **centre** one, in front and the tallest
- `-3` sits on the **right**, tilted forward, partly behind the middle one

`-2` is the most visible of the three, so it is worth giving it the screen that
best sells the app. Each set should show three different screens rather than the
same one three times, and it reads best when every colour set uses the same
three screens, so only the colour appears to change when a visitor switches.

The `tv-` one has no number: there is a single Android TV shot per colour.

## Naming: the gallery

The screenshots in the "See it" row are named after the screen they show, twice
over:

```
<screen>-light.png
<screen>-dark.png
```

Unlike the accent sets, these are named in `index.html` (and in the repository's
own README), so adding one means adding an `<img>` there carrying the two paths.
`script.js` puts up whichever matches the theme on screen, and the two versions
of a screen must share the same pixel dimensions or the page will jump when a
visitor switches theme.

These carry the accent colour they were taken in and are never swapped by the
colour picker, so a set of six can happily show six different colours.

## Format

- **PNG with a transparent background.** The device frame should be part of the
  image and the area around it transparent. The site draws no frame of its own
  and paints nothing behind them, so anything opaque here shows up as a visible
  rectangle on the page.
- Same pixel dimensions across every colour, both for the hero trio and for the
  TV shot, so nothing shifts when a visitor changes colour.
- Same device frame across every colour, otherwise the phone or the TV appears
  to change model rather than just colour.
- No drop shadow baked in. The site adds one that follows the frame's own shape.
- Export as large as the tool gives you. `optimise.py` handles the resizing, and
  keeping the big original means a future redesign can re-derive from it.
