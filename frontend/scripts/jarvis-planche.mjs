#!/usr/bin/env node
// F-236 / SF-236-14 — générateur des planches de référence de Jarvis en 3D (cadrage §15 : « visa du PO à chaque
// itération »). Il lit une description JSON (sections de captures, séquences de transitions) et écrit la page HTML
// autonome, à côté des captures (chemins relatifs).
//
// Réserve de l'itération 1 corrigée : la légende « t = … ms » d'une séquence est lue sur le NOM du fichier par une
// expression complète (`…-t<ms>-<appareil>.png`) — l'ancien découpage sur « -t » tombait sur « -telephone » et
// affichait « t = elephone.png ms ».
//
// Usage : node scripts/jarvis-planche.mjs <planche.json> <sortie.html>
import { readFileSync, writeFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

const DEVICES = { telephone: 'téléphone', pc: 'PC' };

/** `scene-envoi-t660-telephone.png` → `{ scene: 'scene-envoi', t: 660, device: 'telephone' }` ; `null` sinon. */
export function captureName(file) {
  const base = String(file).split('/').pop();
  const match = /^(.+)-t(\d+)-(telephone|pc)\.png$/.exec(base);
  return match ? { scene: match[1], t: Number(match[2]), device: match[3] } : null;
}

/** La légende d'une image de séquence : « t = 660 ms ». */
export function stripCaption(file) {
  const name = captureName(file);
  return name ? `t = ${name.t} ms` : '';
}

function escape(text) {
  return String(text).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

function figure(src, caption, device) {
  return `<figure class="${device}"><img loading="lazy" src="${escape(src)}" alt="${escape(caption)}">`
    + `<figcaption>${escape(caption)}</figcaption></figure>`;
}

/**
 * La planche. `config` : `{ title, intro: [paragraphes], sections: [{ title, items: [{ label, base }] }],
 * sequences: [{ title, base, times: [ms] }], notes: [paragraphes] }`. `base` = chemin relatif sans `-t…-appareil.png`
 * pour une séquence, avec `-t<ms>` pour un item (`../scenes/scene-sgen-t1200`).
 */
export function renderPlanche(config) {
  const parts = [];
  for (const section of config.sections ?? []) {
    const figures = [];
    for (const item of section.items) {
      for (const device of ['telephone', 'pc']) {
        figures.push(figure(`${item.base}-${device}.png`, `${item.label} · ${DEVICES[device]}`, device));
      }
    }
    parts.push(`<section><h2>${escape(section.title)}</h2><div class="grid">${figures.join('')}</div></section>`);
  }
  if (config.sequences?.length) {
    const strips = [];
    for (const seq of config.sequences) {
      for (const device of ['telephone', 'pc']) {
        const frames = seq.times.map((t) => {
          const src = `${seq.base}-t${t}-${device}.png`;
          return figure(src, stripCaption(src), device);
        });
        strips.push(`<h3>${escape(seq.title)} · ${DEVICES[device]}</h3><div class="strip">${frames.join('')}</div>`);
      }
    }
    parts.push(`<section><h2>Transitions (séquences de ${config.sequences[0].times.length} images)</h2>`
      + `${strips.join('')}</section>`);
  }
  const paragraphs = (list) => (list ?? []).map((p) => `<p>${p}</p>`).join('\n');
  return `<!doctype html>
<html lang="fr">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${escape(config.title)}</title>
<style>
  :root { --navy: #0B1020; --orange: #E07B39; --orange-2: #F0954F; --cream: #F5EFE3; --cyan: #5FD4FF; }
  body { margin: 0; padding: 24px 16px 48px; background: var(--navy); color: var(--cream);
    font-family: 'Inter', system-ui, sans-serif; }
  h1, h2, h3 { font-family: 'Space Grotesk', 'Inter', sans-serif; letter-spacing: .06em; text-transform: uppercase; }
  h1 { color: var(--orange-2); font-size: 22px; }
  h2 { color: var(--orange-2); font-size: 16px; margin-top: 40px; border-bottom: 1px solid #F0954F55; padding-bottom: 8px; }
  h3 { font-size: 13px; color: var(--cream); margin: 24px 0 8px; }
  p, li { max-width: 920px; line-height: 1.5; }
  .grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: 12px; }
  .strip { display: flex; gap: 8px; overflow-x: auto; padding-bottom: 8px; }
  .strip figure.pc { flex: 0 0 300px; }
  .strip figure.telephone { flex: 0 0 120px; }
  figure { margin: 0; }
  img { width: 100%; display: block; border: 1px solid #E07B3940; }
  figcaption { font-size: 12px; opacity: .8; margin-top: 4px; }
  .note { border-left: 3px solid var(--cyan); padding: 8px 12px; background: #5FD4FF14; }
</style>
</head>
<body>
<h1>${escape(config.heading ?? config.title)}</h1>
${paragraphs(config.intro)}
${parts.join('\n')}
${paragraphs(config.notes)}
</body>
</html>
`;
}

/** Corrige les légendes d'une planche déjà écrite (itération 1) : chaque « t = … ms » relu sur son image. */
export function fixCaptions(html) {
  return html.replace(/(<img[^>]*src="([^"]+)"[^>]*><figcaption>)t = [^<]* ms(<\/figcaption>)/g,
    (_, head, src, tail) => `${head}${stripCaption(src)}${tail}`);
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? '').href) {
  const [input, output] = process.argv.slice(2);
  if (!input || !output) {
    console.error('usage : node scripts/jarvis-planche.mjs <planche.json|planche.html> <sortie.html>');
    process.exit(2);
  }
  const text = readFileSync(input, 'utf8');
  writeFileSync(output, input.endsWith('.html') ? fixCaptions(text) : renderPlanche(JSON.parse(text)));
  console.log(`planche écrite : ${output}`);
}
