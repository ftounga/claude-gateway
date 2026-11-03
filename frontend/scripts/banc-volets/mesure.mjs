// Mesure la carte des volets (position, hauteur, débordements) à une taille donnée.
import puppeteer from 'puppeteer-core';
import { createServer } from './mock.mjs';
const [w, h] = (process.argv[2] ?? '360x740').split('x').map(Number);
const server = createServer();
const browser = await puppeteer.launch({ executablePath: '/usr/bin/google-chrome', headless: true,
  ignoreDefaultArgs: ['--hide-scrollbars'], args: ['--no-sandbox'] });
const page = await browser.newPage();
await page.setViewport({ width: w, height: h });
await page.setRequestInterception(true);
page.on('request', (req) => (new URL(req.url()).pathname.startsWith('/api/') ? server.handle(req) : req.continue()));
await page.goto('http://localhost:4311/', { waitUntil: 'domcontentloaded' });
await page.evaluate(() => { localStorage.setItem('cg_token', 'x.eyJleHAiOjk5OTk5OTk5OTl9.y'); localStorage.setItem('cg_atelier_guide', JSON.stringify({ dismissed: true })); });
await page.goto('http://localhost:4311/atelier/ws-1', { waitUntil: 'domcontentloaded' });
await page.waitForSelector('app-atelier-terminal', { timeout: 60000 });
await new Promise((r) => setTimeout(r, 3000));
const m = await page.evaluate(() => {
  const r = (sel) => { const e = document.querySelector(sel); if (!e) return null; const b = e.getBoundingClientRect(); return { top: Math.round(b.top), h: Math.round(b.height), w: Math.round(b.width) }; };
  const over = [...document.querySelectorAll('*')].filter((e) => e.scrollWidth > e.clientWidth + 1 && getComputedStyle(e).overflowX !== 'visible' && getComputedStyle(e).overflowX !== 'clip').map((e) => e.className?.toString().slice(0, 40)).slice(0, 5);
  return { map: r('app-terminal-pane-map'), tabs: r('.lane-tabs'), split: r('.lane-split'), main: r('main'), docOverflow: document.documentElement.scrollWidth > innerWidth, over,
    moveBtn: r('.pane-map__move') };
});
console.log(JSON.stringify(m));
await page.screenshot({ path: `mesure-${w}.png` });
await browser.close();
