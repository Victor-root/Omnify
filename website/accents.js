/* The accent colours offered in the header.
 *
 * Picking one repaints the site and swaps the three hero screenshots for a set
 * shot in that same colour, so the page demonstrates the app's own accent
 * picker rather than just describing it.
 *
 * brand is the app's real accent, sampled from the screenshots themselves, so
 * the page and the phones in it are the same colour rather than merely similar.
 *
 * To add a colour: append an entry here, add an "accent.<id>" key to both
 * locales in i18n.js, and drop the screenshots in assets/screenshots (see the
 * README there). Nothing else needs touching, the header builds itself from
 * this list and every tinted value in the CSS derives from brand / brand2.
 *
 *   id       matches the "accent.<id>" label key in i18n.js
 *   brand    the main accent, used at full strength as a surface: buttons,
 *            aurora blobs, swatches. The CSS derives a lighter or darker
 *            counterpart from it for anything that has to read as text.
 *   brand2   the secondary tone: the headline gradient's tail, the second blob
 *   onBrand  text drawn on top of brand at full strength. A dark accent wants
 *            white here, a light one wants ink.
 *   shots    the three hero screenshots, in the order they are stacked:
 *            left (tilted back), centre (in front), right (tilted forward)
 *   tvShot   the Android TV screenshot further down the page
 */

window.OMNIFY_ACCENTS = [
  {
    id: "green",
    brand: "#4caf50",
    brand2: "#ffb780",
    onBrand: "#06130c",
    shots: [
      "assets/screenshots/green-1.webp",
      "assets/screenshots/green-2.webp",
      "assets/screenshots/green-3.webp"
    ],
    tvShot: "assets/screenshots/tv-green.webp"
  },
  {
    id: "red",
    brand: "#d32f2f",
    brand2: "#ff9d5c",
    onBrand: "#ffffff",
    shots: [
      "assets/screenshots/red-1.webp",
      "assets/screenshots/red-2.webp",
      "assets/screenshots/red-3.webp"
    ],
    tvShot: "assets/screenshots/tv-red.webp"
  },
  {
    id: "purple",
    brand: "#673ab7",
    brand2: "#c86dd7",
    onBrand: "#ffffff",
    shots: [
      "assets/screenshots/purple-1.webp",
      "assets/screenshots/purple-2.webp",
      "assets/screenshots/purple-3.webp"
    ],
    tvShot: "assets/screenshots/tv-purple.webp"
  }
];
