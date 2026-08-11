/*
 * Trimmed port of Project Nayuki's QR Code generator library.
 * https://www.nayuki.io/page/qr-code-generator-library
 * Copyright (c) Project Nayuki. MIT License.
 */

/*---- helpers ----*/

function appendBits(val, len, bb) {
  if (len < 0 || len > 31 || (val >>> len) != 0)
    throw new RangeError("Value out of range");
  for (let i = len - 1; i >= 0; i--)
    bb.push((val >>> i) & 1);
}

function getBit(x, i) {
  return ((x >>> i) & 1) != 0;
}

function assert(cond) {
  if (!cond)
    throw new Error("Assertion error");
}

/** UTF-8 encode a string to an array of byte values. */
function toUtf8ByteArray(str) {
  if (typeof TextEncoder !== "undefined")
    return Array.from(new TextEncoder().encode(str));
  const encoded = encodeURI(str);
  const result = [];
  for (let i = 0; i < encoded.length; i++) {
    if (encoded.charAt(i) != "%") {
      result.push(encoded.charCodeAt(i));
    } else {
      result.push(parseInt(encoded.substring(i + 1, i + 3), 16));
      i += 2;
    }
  }
  return result;
}

/*---- Data segment ----*/

class QrSegment {
  constructor(mode, numChars, bitData) {
    if (numChars < 0)
      throw new RangeError("Invalid argument");
    this.mode = mode;
    this.numChars = numChars;
    this.bitData = bitData.slice();
  }

  getData() {
    return this.bitData.slice();
  }

  static makeBytes(data) {
    const bb = [];
    for (const b of data)
      appendBits(b, 8, bb);
    return new QrSegment(QrSegment.Mode.BYTE, data.length, bb);
  }

  // For our use every payload is a URL: byte mode over UTF-8 always encodes it, and skipping the
  // numeric/alphanumeric mode selection keeps this port small.
  static makeSegments(text) {
    if (text == "")
      return [];
    return [QrSegment.makeBytes(toUtf8ByteArray(text))];
  }

  static getTotalBits(segs, version) {
    let result = 0;
    for (const seg of segs) {
      const ccbits = seg.mode.numCharCountBits(version);
      if (seg.numChars >= (1 << ccbits))
        return Infinity; // The segment's length doesn't fit the field's bit width at this version
      result += 4 + ccbits + seg.bitData.length;
    }
    return result;
  }
}

QrSegment.Mode = class Mode {
  constructor(modeBits, numBitsCharCount) {
    this.modeBits = modeBits;
    this.numBitsCharCount = numBitsCharCount;
  }

  numCharCountBits(ver) {
    return this.numBitsCharCount[Math.floor((ver + 7) / 17)];
  }
};

QrSegment.Mode.BYTE = new QrSegment.Mode(0x4, [8, 16, 16]);

/*---- QR Code symbol ----*/

export class QrCode {

  static encodeText(text, ecl) {
    const segs = QrSegment.makeSegments(text);
    return QrCode.encodeSegments(segs, ecl);
  }

  static encodeBinary(data, ecl) {
    const seg = QrSegment.makeBytes(data);
    return QrCode.encodeSegments([seg], ecl);
  }

  static encodeSegments(segs, ecl, minVersion = 1, maxVersion = 40, mask = -1, boostEcl = true) {
    if (!(QrCode.MIN_VERSION <= minVersion && minVersion <= maxVersion && maxVersion <= QrCode.MAX_VERSION)
        || mask < -1 || mask > 7)
      throw new RangeError("Invalid value");

    // Find the smallest version that fits the data
    let version;
    let dataUsedBits;
    for (version = minVersion; ; version++) {
      const capacityBits = QrCode.getNumDataCodewords(version, ecl) * 8;
      const usedBits = QrSegment.getTotalBits(segs, version);
      if (usedBits <= capacityBits) {
        dataUsedBits = usedBits;
        break;
      }
      if (version >= maxVersion)
        throw new RangeError("Data too long");
    }

    // Boost the error-correction level as long as the data still fits
    for (const newEcl of [QrCode.Ecc.MEDIUM, QrCode.Ecc.QUARTILE, QrCode.Ecc.HIGH]) {
      if (boostEcl && dataUsedBits <= QrCode.getNumDataCodewords(version, newEcl) * 8)
        ecl = newEcl;
    }

    // Concatenate all segments to create the data bit string
    const bb = [];
    for (const seg of segs) {
      appendBits(seg.mode.modeBits, 4, bb);
      appendBits(seg.numChars, seg.mode.numCharCountBits(version), bb);
      for (const b of seg.getData())
        bb.push(b);
    }
    assert(bb.length == dataUsedBits);

    // Add terminator and pad up to a byte boundary
    const dataCapacityBits = QrCode.getNumDataCodewords(version, ecl) * 8;
    assert(bb.length <= dataCapacityBits);
    appendBits(0, Math.min(4, dataCapacityBits - bb.length), bb);
    appendBits(0, (8 - bb.length % 8) % 8, bb);
    assert(bb.length % 8 == 0);

    // Pad with alternating bytes until the capacity is reached
    for (let padByte = 0xEC; bb.length < dataCapacityBits; padByte ^= 0xEC ^ 0x11)
      appendBits(padByte, 8, bb);

    // Pack bits into bytes in big-endian order
    const dataCodewords = [];
    while (dataCodewords.length * 8 < bb.length)
      dataCodewords.push(0);
    bb.forEach((b, i) => (dataCodewords[i >>> 3] |= b << (7 - (i & 7))));

    return new QrCode(version, ecl, dataCodewords, mask);
  }

  constructor(version, errorCorrectionLevel, dataCodewords, msk) {
    if (version < QrCode.MIN_VERSION || version > QrCode.MAX_VERSION)
      throw new RangeError("Version value out of range");
    if (msk < -1 || msk > 7)
      throw new RangeError("Mask value out of range");
    this.version = version;
    this.errorCorrectionLevel = errorCorrectionLevel;
    this.size = version * 4 + 17;

    const row = [];
    for (let i = 0; i < this.size; i++)
      row.push(false);
    this.modules = [];
    this.isFunction = [];
    for (let i = 0; i < this.size; i++) {
      this.modules.push(row.slice());
      this.isFunction.push(row.slice());
    }

    this.drawFunctionPatterns();
    const allCodewords = this.addEccAndInterleave(dataCodewords);
    this.drawCodewords(allCodewords);

    if (msk == -1) { // Automatically choose best mask
      let minPenalty = 1000000000;
      for (let i = 0; i < 8; i++) {
        this.applyMask(i);
        this.drawFormatBits(i);
        const penalty = this.getPenaltyScore();
        if (penalty < minPenalty) {
          msk = i;
          minPenalty = penalty;
        }
        this.applyMask(i); // Undoes the mask due to XOR
      }
    }
    assert(0 <= msk && msk <= 7);
    this.mask = msk;
    this.applyMask(msk); // Apply the final choice of mask
    this.drawFormatBits(msk); // Overwrite old format bits

    this.isFunction = [];
  }

  getModule(x, y) {
    return 0 <= x && x < this.size && 0 <= y && y < this.size && this.modules[y][x];
  }

  drawFunctionPatterns() {
    // Draw horizontal and vertical timing patterns
    for (let i = 0; i < this.size; i++) {
      this.setFunctionModule(6, i, i % 2 == 0);
      this.setFunctionModule(i, 6, i % 2 == 0);
    }

    // Draw 3 finder patterns (all corners except bottom right; overwritten by alignment if needed)
    this.drawFinderPattern(3, 3);
    this.drawFinderPattern(this.size - 4, 3);
    this.drawFinderPattern(3, this.size - 4);

    // Draw numerous alignment patterns
    const alignPatPos = this.getAlignmentPatternPositions();
    const numAlign = alignPatPos.length;
    for (let i = 0; i < numAlign; i++) {
      for (let j = 0; j < numAlign; j++) {
        // Don't draw on the three finder corners
        if (!(i == 0 && j == 0 || i == 0 && j == numAlign - 1 || i == numAlign - 1 && j == 0))
          this.drawAlignmentPattern(alignPatPos[i], alignPatPos[j]);
      }
    }

    // Draw configuration data
    this.drawFormatBits(0); // Dummy mask value; overwritten later in the constructor
    this.drawVersion();
  }

  drawFormatBits(mask) {
    // Calculate error correction code and assemble bits
    const data = this.errorCorrectionLevel.formatBits << 3 | mask;
    let rem = data;
    for (let i = 0; i < 10; i++)
      rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
    const bits = (data << 10 | rem) ^ 0x5412; // uint15
    assert(bits >>> 15 == 0);

    // Draw first copy
    for (let i = 0; i <= 5; i++)
      this.setFunctionModule(8, i, getBit(bits, i));
    this.setFunctionModule(8, 7, getBit(bits, 6));
    this.setFunctionModule(8, 8, getBit(bits, 7));
    this.setFunctionModule(7, 8, getBit(bits, 8));
    for (let i = 9; i < 15; i++)
      this.setFunctionModule(14 - i, 8, getBit(bits, i));

    // Draw second copy
    for (let i = 0; i < 8; i++)
      this.setFunctionModule(this.size - 1 - i, 8, getBit(bits, i));
    for (let i = 8; i < 15; i++)
      this.setFunctionModule(8, this.size - 15 + i, getBit(bits, i));
    this.setFunctionModule(8, this.size - 8, true); // Always dark
  }

  drawVersion() {
    if (this.version < 7)
      return;

    // Calculate error correction code and assemble bits
    let rem = this.version;
    for (let i = 0; i < 12; i++)
      rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
    const bits = this.version << 12 | rem; // uint18
    assert(bits >>> 18 == 0);

    // Draw two copies
    for (let i = 0; i < 18; i++) {
      const color = getBit(bits, i);
      const a = this.size - 11 + i % 3;
      const b = Math.floor(i / 3);
      this.setFunctionModule(a, b, color);
      this.setFunctionModule(b, a, color);
    }
  }

  drawFinderPattern(x, y) {
    for (let dy = -4; dy <= 4; dy++) {
      for (let dx = -4; dx <= 4; dx++) {
        const dist = Math.max(Math.abs(dx), Math.abs(dy)); // Chebyshev/infinity norm
        const xx = x + dx;
        const yy = y + dy;
        if (0 <= xx && xx < this.size && 0 <= yy && yy < this.size)
          this.setFunctionModule(xx, yy, dist != 2 && dist != 4);
      }
    }
  }

  drawAlignmentPattern(x, y) {
    for (let dy = -2; dy <= 2; dy++) {
      for (let dx = -2; dx <= 2; dx++)
        this.setFunctionModule(x + dx, y + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
    }
  }

  setFunctionModule(x, y, isDark) {
    this.modules[y][x] = isDark;
    this.isFunction[y][x] = true;
  }

  addEccAndInterleave(data) {
    const ver = this.version;
    const ecl = this.errorCorrectionLevel;
    if (data.length != QrCode.getNumDataCodewords(ver, ecl))
      throw new RangeError("Invalid argument");

    // Calculate parameter numbers
    const numBlocks = QrCode.NUM_ERROR_CORRECTION_BLOCKS[ecl.ordinal][ver];
    const blockEccLen = QrCode.ECC_CODEWORDS_PER_BLOCK[ecl.ordinal][ver];
    const rawCodewords = Math.floor(QrCode.getNumRawDataModules(ver) / 8);
    const numShortBlocks = numBlocks - rawCodewords % numBlocks;
    const shortBlockLen = Math.floor(rawCodewords / numBlocks);

    // Split data into blocks and append ECC to each block
    const blocks = [];
    const rsDiv = QrCode.reedSolomonComputeDivisor(blockEccLen);
    for (let i = 0, k = 0; i < numBlocks; i++) {
      const dat = data.slice(k, k + shortBlockLen - blockEccLen + (i < numShortBlocks ? 0 : 1));
      k += dat.length;
      const ecc = QrCode.reedSolomonComputeRemainder(dat, rsDiv);
      if (i < numShortBlocks)
        dat.push(0);
      blocks.push(dat.concat(ecc));
    }

    // Interleave (not concatenate) the bytes from every block into a single sequence
    const result = [];
    for (let i = 0; i < blocks[0].length; i++) {
      blocks.forEach((block, j) => {
        // Skip the padding byte in short blocks
        if (i != shortBlockLen - blockEccLen || j >= numShortBlocks)
          result.push(block[i]);
      });
    }
    assert(result.length == rawCodewords);
    return result;
  }

  drawCodewords(data) {
    if (data.length != Math.floor(QrCode.getNumRawDataModules(this.version) / 8))
      throw new RangeError("Invalid argument");
    let i = 0; // Bit index into the data
    // Do the funny zigzag scan
    for (let right = this.size - 1; right >= 1; right -= 2) { // Index of right column in each column pair
      if (right == 6)
        right = 5;
      for (let vert = 0; vert < this.size; vert++) { // Vertical counter
        for (let j = 0; j < 2; j++) {
          const x = right - j; // Actual x coordinate
          const upward = ((right + 1) & 2) == 0;
          const y = upward ? this.size - 1 - vert : vert; // Actual y coordinate
          if (!this.isFunction[y][x] && i < data.length * 8) {
            this.modules[y][x] = getBit(data[i >>> 3], 7 - (i & 7));
            i++;
          }
          // If this QR Code has any remainder bits (0 to 7), they were assigned as 0/false/light
          // by the constructor and are left unchanged by this method
        }
      }
    }
    assert(i == data.length * 8);
  }

  applyMask(mask) {
    if (mask < 0 || mask > 7)
      throw new RangeError("Mask value out of range");
    for (let y = 0; y < this.size; y++) {
      for (let x = 0; x < this.size; x++) {
        let invert;
        switch (mask) {
          case 0: invert = (x + y) % 2 == 0; break;
          case 1: invert = y % 2 == 0; break;
          case 2: invert = x % 3 == 0; break;
          case 3: invert = (x + y) % 3 == 0; break;
          case 4: invert = (Math.floor(x / 3) + Math.floor(y / 2)) % 2 == 0; break;
          case 5: invert = x * y % 2 + x * y % 3 == 0; break;
          case 6: invert = (x * y % 2 + x * y % 3) % 2 == 0; break;
          case 7: invert = ((x + y) % 2 + x * y % 3) % 2 == 0; break;
          default: throw new Error("Unreachable");
        }
        if (!this.isFunction[y][x] && invert)
          this.modules[y][x] = !this.modules[y][x];
      }
    }
  }

  getPenaltyScore() {
    let result = 0;

    // Adjacent modules in row having same color, and finder-like patterns
    for (let y = 0; y < this.size; y++) {
      let runColor = false;
      let runX = 0;
      const runHistory = [0, 0, 0, 0, 0, 0, 0];
      for (let x = 0; x < this.size; x++) {
        if (this.modules[y][x] == runColor) {
          runX++;
          if (runX == 5)
            result += QrCode.PENALTY_N1;
          else if (runX > 5)
            result++;
        } else {
          this.finderPenaltyAddHistory(runX, runHistory);
          if (!runColor)
            result += this.finderPenaltyCountPatterns(runHistory) * QrCode.PENALTY_N3;
          runColor = this.modules[y][x];
          runX = 1;
        }
      }
      result += this.finderPenaltyTerminateAndCount(runColor, runX, runHistory) * QrCode.PENALTY_N3;
    }

    // Adjacent modules in column having same color, and finder-like patterns
    for (let x = 0; x < this.size; x++) {
      let runColor = false;
      let runY = 0;
      const runHistory = [0, 0, 0, 0, 0, 0, 0];
      for (let y = 0; y < this.size; y++) {
        if (this.modules[y][x] == runColor) {
          runY++;
          if (runY == 5)
            result += QrCode.PENALTY_N1;
          else if (runY > 5)
            result++;
        } else {
          this.finderPenaltyAddHistory(runY, runHistory);
          if (!runColor)
            result += this.finderPenaltyCountPatterns(runHistory) * QrCode.PENALTY_N3;
          runColor = this.modules[y][x];
          runY = 1;
        }
      }
      result += this.finderPenaltyTerminateAndCount(runColor, runY, runHistory) * QrCode.PENALTY_N3;
    }

    // 2*2 blocks of modules having same color
    for (let y = 0; y < this.size - 1; y++) {
      for (let x = 0; x < this.size - 1; x++) {
        const color = this.modules[y][x];
        if (color == this.modules[y][x + 1] &&
            color == this.modules[y + 1][x] &&
            color == this.modules[y + 1][x + 1])
          result += QrCode.PENALTY_N2;
      }
    }

    // Balance of dark and light modules
    let dark = 0;
    for (const row of this.modules)
      dark = row.reduce((sum, color) => sum + (color ? 1 : 0), dark);
    const total = this.size * this.size; // Note that size is odd, so dark/total != 1/2
    // Compute the smallest integer k >= 0 such that (45-5k)% <= dark/total <= (55+5k)%
    const k = Math.ceil(Math.abs(dark * 20 - total * 10) / total) - 1;
    assert(0 <= k && k <= 9);
    result += k * QrCode.PENALTY_N4;
    assert(0 <= result && result <= 2568888); // Non-tight upper bound based on default values
    return result;
  }

  getAlignmentPatternPositions() {
    if (this.version == 1)
      return [];
    const numAlign = Math.floor(this.version / 7) + 2;
    const step = (this.version == 32) ? 26 :
      Math.ceil((this.size - 13) / (numAlign * 2 - 2)) * 2;
    const result = [6];
    for (let pos = this.size - 7; result.length < numAlign; pos -= step)
      result.splice(1, 0, pos);
    return result;
  }

  static getNumRawDataModules(ver) {
    if (ver < QrCode.MIN_VERSION || ver > QrCode.MAX_VERSION)
      throw new RangeError("Version number out of range");
    let result = (16 * ver + 128) * ver + 64;
    if (ver >= 2) {
      const numAlign = Math.floor(ver / 7) + 2;
      result -= (25 * numAlign - 10) * numAlign - 55;
      if (ver >= 7)
        result -= 36;
    }
    assert(208 <= result && result <= 29648);
    return result;
  }

  static getNumDataCodewords(ver, ecl) {
    return Math.floor(QrCode.getNumRawDataModules(ver) / 8) -
      QrCode.ECC_CODEWORDS_PER_BLOCK[ecl.ordinal][ver] *
      QrCode.NUM_ERROR_CORRECTION_BLOCKS[ecl.ordinal][ver];
  }

  static reedSolomonComputeDivisor(degree) {
    if (degree < 1 || degree > 255)
      throw new RangeError("Degree out of range");
    // Polynomial coefficients are stored from highest to lowest power, excluding the leading term
    const result = [];
    for (let i = 0; i < degree - 1; i++)
      result.push(0);
    result.push(1); // Start with the monomial x^0

    // Compute the product polynomial (x - r^0)(x - r^1)...(x - r^(degree-1))
    let root = 1;
    for (let i = 0; i < degree; i++) {
      for (let j = 0; j < result.length; j++) {
        result[j] = QrCode.reedSolomonMultiply(result[j], root);
        if (j + 1 < result.length)
          result[j] ^= result[j + 1];
      }
      root = QrCode.reedSolomonMultiply(root, 0x02);
    }
    return result;
  }

  static reedSolomonComputeRemainder(data, divisor) {
    const result = divisor.map(() => 0);
    for (const b of data) {
      const factor = b ^ result.shift();
      result.push(0);
      divisor.forEach((coef, i) => (result[i] ^= QrCode.reedSolomonMultiply(coef, factor)));
    }
    return result;
  }

  static reedSolomonMultiply(x, y) {
    if (x >>> 8 != 0 || y >>> 8 != 0)
      throw new RangeError("Byte out of range");
    // Russian peasant multiplication in GF(2^8)/0x11D
    let z = 0;
    for (let i = 7; i >= 0; i--) {
      z = (z << 1) ^ ((z >>> 7) * 0x11D);
      z ^= ((y >>> i) & 1) * x;
    }
    assert(z >>> 8 == 0);
    return z;
  }

  finderPenaltyCountPatterns(runHistory) {
    const n = runHistory[1];
    assert(n <= this.size * 3);
    const core = n > 0 && runHistory[2] == n && runHistory[3] == n * 3 && runHistory[4] == n && runHistory[5] == n;
    return (core && runHistory[0] >= n * 4 && runHistory[6] >= n ? 1 : 0)
      + (core && runHistory[6] >= n * 4 && runHistory[0] >= n ? 1 : 0);
  }

  finderPenaltyTerminateAndCount(currentRunColor, currentRunLength, runHistory) {
    if (currentRunColor) { // Terminate dark run
      this.finderPenaltyAddHistory(currentRunLength, runHistory);
      currentRunLength = 0;
    }
    currentRunLength += this.size; // Add light border to final run
    this.finderPenaltyAddHistory(currentRunLength, runHistory);
    return this.finderPenaltyCountPatterns(runHistory);
  }

  finderPenaltyAddHistory(currentRunLength, runHistory) {
    if (runHistory[0] == 0)
      currentRunLength += this.size; // Add light border to initial run
    runHistory.pop();
    runHistory.unshift(currentRunLength);
  }
}

QrCode.MIN_VERSION = 1;
QrCode.MAX_VERSION = 40;

QrCode.PENALTY_N1 = 3;
QrCode.PENALTY_N2 = 3;
QrCode.PENALTY_N3 = 40;
QrCode.PENALTY_N4 = 10;

QrCode.ECC_CODEWORDS_PER_BLOCK = [
  // Version: (note that index 0 is for padding, and is set to an illegal value)
  //0,  1,  2,  3,  4,  5,  6,  7,  8,  9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40    Error correction level
  [-1,  7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28, 28, 28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30], // Low
  [-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28], // Medium
  [-1, 13, 22, 18, 26, 18, 24, 18, 22, 20, 24, 28, 26, 24, 20, 30, 24, 28, 28, 26, 30, 28, 30, 30, 30, 30, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30], // Quartile
  [-1, 17, 28, 22, 16, 22, 28, 26, 26, 24, 28, 24, 28, 22, 24, 24, 30, 28, 28, 26, 28, 30, 24, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30], // High
];

QrCode.NUM_ERROR_CORRECTION_BLOCKS = [
  // Version: (note that index 0 is for padding, and is set to an illegal value)
  //0,  1,  2,  3,  4,  5,  6,  7,  8,  9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40    Error correction level
  [-1,  1,  1,  1,  1,  1,  2,  2,  2,  2,  4,  4,  4,  4,  4,  6,  6,  6,  6,  7,  8,  8,  9,  9, 10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24, 25], // Low
  [-1,  1,  1,  1,  2,  2,  4,  4,  4,  5,  5,  5,  8,  9,  9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49], // Medium
  [-1,  1,  1,  2,  2,  4,  4,  6,  6,  8,  8,  8, 10, 12, 16, 12, 17, 16, 18, 21, 20, 23, 23, 25, 27, 29, 34, 34, 35, 38, 40, 43, 45, 48, 51, 53, 56, 59, 62, 65, 68], // Quartile
  [-1,  1,  1,  2,  4,  4,  4,  5,  6,  8,  8, 11, 11, 16, 16, 18, 16, 19, 21, 25, 25, 25, 34, 30, 32, 35, 37, 40, 42, 45, 48, 51, 54, 57, 60, 63, 66, 70, 74, 77, 81], // High
];

QrCode.Ecc = class Ecc {
  constructor(ordinal, formatBits) {
    this.ordinal = ordinal;
    this.formatBits = formatBits;
  }
};

QrCode.Ecc.LOW = new QrCode.Ecc(0, 1);
QrCode.Ecc.MEDIUM = new QrCode.Ecc(1, 0);
QrCode.Ecc.QUARTILE = new QrCode.Ecc(2, 3);
QrCode.Ecc.HIGH = new QrCode.Ecc(3, 2);
