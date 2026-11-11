// F-256 — RELIT un passage déjà fait (`rapport.json`, sondes brutes comprises) avec la mesure et la porte actuelles :
// comparer deux versions sans rejouer, ou corriger la mesure sans repayer les fournisseurs.
//   node relire.mjs <rapport.json>
import { readFile } from 'node:fs/promises';

import { analyse, summary } from './mesure.mjs';
import { gate } from './porte.mjs';

const saved = JSON.parse(await readFile(process.argv[2], 'utf8'));
const report = analyse(saved.runs, {});
report.totals.costEur = saved.totals?.costEur ?? null;
report.meta = saved.meta;
report.gate = gate(report);
console.log(summary(report));
