// F-236 / SF-236-14 — le générateur de planches : légendes des séquences (réserve de l'itération 1) et contenu.
import assert from 'node:assert/strict';

import { captureName, fixCaptions, renderPlanche, stripCaption } from './jarvis-planche.mjs';

// La réserve : « reveil-t0-telephone.png » donnait « t = elephone.png ms ».
assert.equal(stripCaption('../coeur/reveil-t0-telephone.png'), 't = 0 ms');
assert.equal(stripCaption('../coeur/reveil-t480-pc.png'), 't = 480 ms');
assert.equal(stripCaption('../transitions/scene-attente-levee-t140-telephone.png'), 't = 140 ms');
assert.deepEqual(captureName('a/b/entree-courte-t2600-pc.png'), { scene: 'entree-courte', t: 2600, device: 'pc' });
assert.equal(captureName('pas-une-capture.png'), null);

const broken = '<figure class="telephone"><img loading="lazy" src="../coeur/reveil-t120-telephone.png" alt="x">'
  + '<figcaption>t = elephone.png ms</figcaption></figure>';
assert.equal(fixCaptions(broken).includes('t = 120 ms'), true);
assert.equal(fixCaptions(broken).includes('elephone.png ms'), false);

const html = renderPlanche({
  title: 'Planche test', intro: ['Intro'],
  sections: [{ title: 'Scènes', items: [{ label: 'S-GEN', base: '../scenes/scene-sgen-t1200' }] }],
  sequences: [{ title: 'T-P1', base: '../panneaux/panneau-entree', times: [0, 84] }],
});
assert.equal(html.includes('src="../scenes/scene-sgen-t1200-telephone.png"'), true);
assert.equal(html.includes('src="../scenes/scene-sgen-t1200-pc.png"'), true);
assert.equal(html.includes('<figcaption>t = 84 ms</figcaption>'), true);
assert.equal(html.includes('elephone.png ms'), false);
assert.equal((html.match(/<img /g) ?? []).length, 2 + 4);
console.log('jarvis-planche : OK');
