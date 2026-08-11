// Keep QR output black-on-white; dark-inverted codes fail on some phone scanners.

import { QrCode } from "qrcode";

export function qrSvg(text, options) {
  const opts = options || {};
  const border = Number.isFinite(opts.border) ? Math.max(0, Math.floor(opts.border)) : 4;
  const qr = QrCode.encodeText(String(text), eccLevel(opts.ecl));
  const dim = qr.size + border * 2;

  let path = "";
  for (let y = 0; y < qr.size; y++) {
    for (let x = 0; x < qr.size; x++) {
      if (qr.getModule(x, y)) {
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

function eccLevel(name) {
  switch (String(name || "M").toUpperCase()) {
    case "L": return QrCode.Ecc.LOW;
    case "Q": return QrCode.Ecc.QUARTILE;
    case "H": return QrCode.Ecc.HIGH;
    default:  return QrCode.Ecc.MEDIUM;
  }
}
