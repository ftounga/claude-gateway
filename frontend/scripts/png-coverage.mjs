#!/usr/bin/env node
// F-236 / SF-236-01 — part des pixels « hors fond » d'une capture PNG (critère « non vide » du banc 3D).
//
// Un pixel est hors fond si sa distance (somme des écarts R+G+B) au navy `#0B1020` dépasse le seuil : la lueur
// d'abysse seule n'y suffit pas, il faut des étoiles, du sol, un cœur. Décodeur PNG minimal (8 bits, RGB/RGBA,
// non entrelacé : ce que produit Chrome).
//
// Usage : node scripts/png-coverage.mjs capture.png [seuil=60]  → imprime la part (0-1) ; code 3 si illisible.
import { readFileSync } from 'node:fs';
import { inflateSync } from 'node:zlib';

export function decodePng(buffer) {
  if (buffer.readUInt32BE(0) !== 0x89504e47) throw new Error('pas un PNG');
  let offset = 8;
  let width = 0;
  let height = 0;
  let colorType = 0;
  let bitDepth = 0;
  let interlace = 0;
  const idat = [];
  while (offset < buffer.length) {
    const length = buffer.readUInt32BE(offset);
    const type = buffer.toString('ascii', offset + 4, offset + 8);
    const data = buffer.subarray(offset + 8, offset + 8 + length);
    if (type === 'IHDR') {
      width = data.readUInt32BE(0);
      height = data.readUInt32BE(4);
      bitDepth = data[8];
      colorType = data[9];
      interlace = data[12];
    } else if (type === 'IDAT') {
      idat.push(data);
    } else if (type === 'IEND') {
      break;
    }
    offset += 12 + length;
  }
  if (bitDepth !== 8 || interlace !== 0 || (colorType !== 2 && colorType !== 6)) {
    throw new Error(`PNG non pris en charge (profondeur ${bitDepth}, type ${colorType}, entrelacé ${interlace})`);
  }
  const channels = colorType === 6 ? 4 : 3;
  const raw = inflateSync(Buffer.concat(idat));
  const stride = width * channels;
  const pixels = Buffer.alloc(height * stride);
  for (let y = 0; y < height; y++) {
    const filter = raw[y * (stride + 1)];
    const line = raw.subarray(y * (stride + 1) + 1, (y + 1) * (stride + 1));
    for (let x = 0; x < stride; x++) {
      const a = x >= channels ? pixels[y * stride + x - channels] : 0;
      const b = y > 0 ? pixels[(y - 1) * stride + x] : 0;
      const c = x >= channels && y > 0 ? pixels[(y - 1) * stride + x - channels] : 0;
      let value = line[x];
      if (filter === 1) value += a;
      else if (filter === 2) value += b;
      else if (filter === 3) value += (a + b) >> 1;
      else if (filter === 4) {
        const p = a + b - c;
        const pa = Math.abs(p - a);
        const pb = Math.abs(p - b);
        const pc = Math.abs(p - c);
        value += pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
      }
      pixels[y * stride + x] = value & 255;
    }
  }
  return { width, height, channels, pixels };
}

export function coverage(png, threshold = 60, background = [0x0b, 0x10, 0x20]) {
  const { width, height, channels, pixels } = png;
  let off = 0;
  for (let i = 0; i < width * height; i++) {
    const o = i * channels;
    const d = Math.abs(pixels[o] - background[0]) + Math.abs(pixels[o + 1] - background[1])
      + Math.abs(pixels[o + 2] - background[2]);
    if (d > threshold) off++;
  }
  return off / (width * height);
}

if (import.meta.url === `file://${process.argv[1]}`) {
  try {
    const png = decodePng(readFileSync(process.argv[2]));
    console.log(coverage(png, Number(process.argv[3] ?? 60)).toFixed(4));
  } catch (error) {
    console.error(`png-coverage : ${error.message}`);
    process.exit(3);
  }
}
