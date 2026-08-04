# Omnify website

The landing page published at GitHub Pages. Plain HTML, CSS and JavaScript with
no build step, no framework and no external dependency at runtime, so it stays
fast and there is nothing to keep up to date besides the content itself.

## Preview locally

```sh
cd website
python3 -m http.server 8765
```

Then open http://127.0.0.1:8765.

## Deployment

Pushing to `main` with any change under `website/` triggers
`.github/workflows/deploy_website.yml`, which publishes this folder to GitHub
Pages. It can also be run manually from the Actions tab.

This needs to be enabled once: repository **Settings** then **Pages**, with
**GitHub Actions** selected as the source.

## Translations

All copy lives in `i18n.js`, keyed by the `data-i18n` attributes in
`index.html`. English is the source text and the fallback for any key a locale
does not define.

The language is picked from the browser's own `navigator.languages` on first
visit, matching on the base tag so `fr-CA` and `fr-BE` both land on French. A
visitor can override it with the button in the header, and that choice is
remembered. Anything not covered falls back to English.

To add a locale: copy the `en` block in `i18n.js`, translate the values, and key
it by its language code. The header button cycles through whatever is defined,
so nothing else needs changing.

Variants of the same three attributes cover every case:

- `data-i18n="key"` replaces the element's text
- `data-i18n-html="key"` replaces its markup, for strings containing a link or `<strong>`
- `data-i18n-attr="aria-label:key"` translates an attribute

## Notes

- The logo (`assets/omnify-logo.svg`) is the app's own launcher mark, converted
  from `app/src/main/res/drawable/ic_omnify_logo.xml`, so the site and the app
  never drift apart. Same for the `#7ADA9D` / `#FFB780` brand pair and the
  drifting aurora background, which mirrors the one the app renders behind its
  own screens.
- Icons are [Tabler](https://tabler.io/icons) outlines, inlined as an SVG sprite,
  matching the set already used inside the app.
- Screenshots are hotlinked from the same GitHub attachment URLs the README
  uses. To make the site fully self-contained instead, drop the images in
  `assets/` and point the `src` attributes at them.
