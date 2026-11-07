// F-250 / SF-250-08 — mesure i/s (rAF) : cœur vs visage, PC et téléphone, Chrome sans tête.
// GPU=1 : rendu matériel si disponible (--use-gl=egl) ; sinon swiftshader (logiciel, borne basse).
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { extname, join } from 'node:path';
import puppeteer from 'puppeteer-core';
const [dist, ...scenes] = process.argv.slice(2);
const sizes = (process.env.JARVIS_SIZES ?? '1600x900:pc:haut 390x844:telephone:bas').split(' ');
const T = { '.js': 'text/javascript', '.css': 'text/css', '.html': 'text/html' };
const server = createServer(async (req, res) => {
  const p = decodeURIComponent(new URL(req.url, 'http://x').pathname);
  if (p.startsWith('/api/')) { res.writeHead(503); res.end('{}'); return; }
  let f = join(dist, p);
  if (!existsSync(f) || !extname(p)) f = join(dist, 'index.html');
  try { res.writeHead(200, { 'Content-Type': T[extname(f)] ?? 'application/octet-stream' }); res.end(await readFile(f)); }
  catch { res.writeHead(404); res.end(); }
});
await new Promise((r) => server.listen(4351, r));
const args = ['--no-sandbox', '--ignore-gpu-blocklist', '--disable-frame-rate-limit', '--disable-gpu-vsync'];
args.push(...(process.env.GPU ? ['--use-gl=egl', '--enable-gpu'] : ['--use-angle=swiftshader', '--enable-unsafe-swiftshader']));
const browser = await puppeteer.launch({ executablePath: '/usr/bin/google-chrome', headless: true, args });
for (const scene of scenes) {
  for (const size of sizes) {
    const [wh, device, level] = size.split(':');
    const [w, h] = wh.split('x').map(Number);
    const page = await browser.newPage();
    await page.setViewport({ width: w, height: h, deviceScaleFactor: device === 'telephone' ? 2 : 1 });
    await page.goto(`http://localhost:4351/jarvis-banc?banc=${scene}&t=2600&niveau=${level}`, { waitUntil: 'load' });
    await new Promise((r) => setTimeout(r, 8000));
    const r = await page.evaluate(() => new Promise((done) => {
      const times = [];
      let last = performance.now();
      const start = last;
      const tick = (now) => {
        times.push(now - last); last = now;
        if (now - start < 5000) requestAnimationFrame(tick); else done(times);
      };
      requestAnimationFrame(tick);
    }));
    const fps = r.length / 5;
    const sorted = r.slice(1).sort((a, b) => a - b);
    const p50 = 1000 / sorted[Math.floor(sorted.length / 2)];
    const gl = await page.evaluate(() => {
      const c = document.createElement('canvas').getContext('webgl2');
      const d = c?.getExtension('WEBGL_debug_renderer_info');
      return d ? c.getParameter(d.UNMASKED_RENDERER_WEBGL) : 'inconnu';
    });
    console.log(JSON.stringify({ scene, device, level, fps: +fps.toFixed(1), p50: +p50.toFixed(1), renderer: gl }));
    await page.close();
  }
}
await browser.close();
server.close();
