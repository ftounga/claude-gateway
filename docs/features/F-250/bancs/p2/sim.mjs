// Simule la chaîne des lèvres à 60 i/s sur un alignement réel : LipSync (frise + avance 30 ms) → faceLookAt (ressort).
import { readFileSync, writeFileSync } from 'node:fs';
import { LipSync } from './js/face/jarvis-lip-sync.js';
import { faceLookAt, faceMoodTargets } from './js/face/jarvis-face-motion.js';
const [file, out] = process.argv.slice(2);
const al = JSON.parse(readFileSync(file, 'utf8'));
let at = 0;
const lips = new LipSync({ playhead: () => at, alignment: () => al, level: () => 0 });
const look = faceMoodTargets('parole');
const m = { open: 0, wide: 0, round: 0, press: 0 };
const rows = ['t,open,press,raw_open,raw_press'];
const end = al.ends[al.ends.length - 1] + 0.3;
// Le visage est dessiné à l'image suivante : la bouche affichée à l'instant t a été calculée sur l'audio à t − 1 image.
for (let frame = 0; frame * (1 / 60) < end; frame++) {
  at = frame / 60;
  lips.read(m);
  faceLookAt(look, 'parole', at, at, 1 / 60, 0, m, false);
  rows.push([(at + 1 / 60).toFixed(4), look.mouth.open.toFixed(4), look.mouth.press.toFixed(4), m.open.toFixed(4), m.press.toFixed(4)].join(','));
}
writeFileSync(out, rows.join('\n'));
import { visemesFromAlignment } from './js/face/jarvis-visemes.js';
writeFileSync(out.replace('.csv', '.events.json'), JSON.stringify(visemesFromAlignment(al)));
