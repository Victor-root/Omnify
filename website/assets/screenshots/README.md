# Hero screenshots

Drop the accent-colour screenshot sets here as PNG, then run `optimise.py`.
The PNGs are the source, the `.webp` files it produces are what the site serves,
and `accents.js` points at those by file name, so no code needs editing when a
set is added or replaced.

    python3 optimise.py

A 1472px export weighs about 2.3MB; the same screenshot at the size this page
actually draws it is nearer 100KB. That is the whole reason for the step.

## Naming

```
<accent-id>-1.png
<accent-id>-2.png
<accent-id>-3.png
```

`<accent-id>` is the `id` of the matching entry in `../../accents.js`, so today:

```
green-1.png   green-2.png   green-3.png
red-1.png     red-2.png     red-3.png
purple-1.png  purple-2.png  purple-3.png
```

Adding a fourth colour means adding its entry to `accents.js`, a label to both
locales in `i18n.js`, and three files named after its id.

## What the numbers mean

The three are stacked, not shown side by side, so the order decides the layout:

- `-1` sits on the **left**, tilted back, partly behind the middle one
- `-2` is the **centre** one, in front and the tallest
- `-3` sits on the **right**, tilted forward, partly behind the middle one

`-2` is the most visible of the three, so it is worth giving it the screen that
best sells the app. Each set should show three different screens rather than the
same one three times, and it reads best when every colour set uses the same
three screens, so only the colour appears to change when a visitor switches.

## Format

- **PNG with a transparent background.** The device frame should be part of the
  image and the area around it transparent. The site draws no frame of its own
  and paints nothing behind them, so anything opaque here shows up as a visible
  rectangle on the page.
- Same pixel dimensions across every set, so the hero layout does not shift when
  a visitor changes colour.
- No drop shadow baked in. The site adds one that follows the frame's own shape.
- Export as large as the tool gives you. `optimise.py` handles the resizing, and
  keeping the big original means a future redesign can re-derive from it.
