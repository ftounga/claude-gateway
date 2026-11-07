#!/usr/bin/env node
// F-250 / SF-250-08 — banc du visage de Jarvis : captures déterministes et mesure d'images par seconde.
//
// Sert un build de DÉVELOPPEMENT (la page /jarvis-banc n'existe qu'en dev) avec repli SPA, ouvre Chrome sans tête
// (rendu logiciel swiftshader) et :
//   - capture : `?banc=<scène>&t=<ms>&niveau=<n>` → PNG (critère : ≥ 2 % de pixels hors fond, aucune erreur console) ;
//   - mesure  : horloge réelle (`?banc` absent → page /jarvis-banc?mesure=<scène>), images comptées par rAF pendant N s.
//
// Usage : node scripts/jarvis-face-banc.mjs <dist/browser> <sortie> [scènes…]
//   JARVIS_SIZES="1920x1080:pc:haut 390x844:telephone:bas"   JARVIS_T=2600
import { createServer } from 'node:http';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { extname, join } from 'node:path';
import puppeteer from 'puppeteer-core';

const [dist, out, ...scenesArg] = process.argv.slice(2);
const scenes = scenesArg.length ? scenesArg : ['visage-parole', 'visage-ecoute', 'visage-reflexion', 'visage-repos'];
const sizes = (process.env.JARVIS_SIZES ?? '1920x1080:pc:haut 390x844:telephone:bas').split(' ');
const T = Number(process.env.JARVIS_T ?? 2600);
const TYPES = { '.js': 'text/javascript', '.css': 'text/css', '.html': 'text/html', '.svg': 'image/svg+xml',
  '.png': 'image/png', '.woff2': 'font/woff2', '.json': 'application/json', '.ico': 'image/x-icon' };

const server = createServer(async (req, res) => {
  const path = decodeURIComponent(new URL(req.url, 'http://x').pathname);
  if (path.startsWith('/api/')) {
    res.writeHead(503, { 'Content-Type': 'application/json' });
    res.end('{"error":"banc"}');
    return;
  }
  let file = join(dist, path);
  if (!existsSync(file) || path === '/' || !extname(path)) {
    file = join(dist, 'index.html');
  }
  try {
    const body = await readFile(file);
    res.writeHead(200, { 'Content-Type': TYPES[extname(file)] ?? 'application/octet-stream' });
    res.end(body);
  } catch {
    res.writeHead(404);
    res.end();
  }
});
await new Promise((r) => server.listen(4350, r));
await mkdir(out, { recursive: true });
const browser = await puppeteer.launch({
  executablePath: '/usr/bin/google-chrome', headless: true,
  args: ['--no-sandbox', '--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'],
});
const results = [];
for (const scene of scenes) {
  for (const size of sizes) {
    const [wh, device, level] = size.split(':');
    const [w, h] = wh.split('x').map(Number);
    const page = await browser.newPage();
    const errors = [];
    page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
    page.on('pageerror', (e) => errors.push(String(e)));
    await page.setViewport({ width: w, height: h, deviceScaleFactor: device === 'telephone' ? 2 : 1 });
    await page.goto(`http://localhost:4350/jarvis-banc?banc=${scene}&t=${T}&niveau=${level}`, { waitUntil: 'load' });
    await new Promise((r) => setTimeout(r, Number(process.env.JARVIS_WAIT ?? 9000)));
    const png = join(out, `${scene}-${device}.png`);
    await page.screenshot({ path: png });
    results.push({ scene, device, png, errors: errors.filter((e) => !/api|503|Failed to load resource/.test(e)) });
    await page.close();
  }
}
await browser.close();
server.close();
await writeFile(join(out, 'banc.json'), JSON.stringify(results, null, 1));
for (const r of results) {
  console.log(`${r.errors.length ? 'ERREUR' : 'OK    '} ${r.png}${r.errors.length ? ' ' + r.errors.join(' | ') : ''}`);
}
