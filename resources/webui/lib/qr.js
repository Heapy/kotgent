/*
 * QR rendering: a string (a one-time login URL) → inline SVG markup.
 *
 * SVG, not canvas: it is a single `<path>` over a white quiet-zone, so it stays crisp at any size,
 * needs no pixel dimensions from the caller (CSS sizes it through the viewBox), and can be dropped
 * straight into the DOM. The colors are fixed black-on-white rather than theme-aware on purpose — a
 * dark-inverted QR trips some phone scanners, and the code has to scan on the first try.
 *
 * The QR itself comes from the vendored, dependency-free generator, reached through the import map
 * (`"qrcode"`) like preact/htm.
 */

import { QrCode } from "qrcode";

/**
 * Render [text] as a self-contained QR-code `<svg>` string.
 *
 * @param text the payload to encode (here, a full `https://…/auth#ticket=…` login URL).
 * @param options.border quiet-zone width in modules (spec minimum is 4); options.ecl the
 *   error-correction level, one of "L" | "M" | "Q" | "H" (default "M").
 * @returns `<svg>…</svg>` sized in module units via `viewBox`, so CSS decides its pixel size.
 */
export function qrSvg(text, options) {
  const opts = options || {};
  const border = Number.isFinite(opts.border) ? Math.max(0, Math.floor(opts.border)) : 4;
  const qr = QrCode.encodeText(String(text), eccLevel(opts.ecl));
  const dim = qr.size + border * 2;

  let path = "";
  for (let y = 0; y < qr.size; y++) {
    for (let x = 0; x < qr.size; x++) {
      if (qr.getModule(x, y)) {
        // One 1x1 square per dark module; the whole set is a single path fill.
        path += (path ? " " : "") + "M" + (x + border) + "," + (y + border) + "h1v1h-1z";
      }
    }
  }

  return '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ' + dim + " " + dim + '"' +
    ' shape-rendering="crispEdges" role="img" aria-label="QR code">' +
    '<rect width="100%" height="100%" fill="#ffffff"/>' +
    '<path d="' + path + '" fill="#000000"/>' +
    "</svg>";
}

/** Map an "L"|"M"|"Q"|"H" name (default "M") to the generator's error-correction constant. */
function eccLevel(name) {
  switch (String(name || "M").toUpperCase()) {
    case "L": return QrCode.Ecc.LOW;
    case "Q": return QrCode.Ecc.QUARTILE;
    case "H": return QrCode.Ecc.HIGH;
    default:  return QrCode.Ecc.MEDIUM;
  }
}
