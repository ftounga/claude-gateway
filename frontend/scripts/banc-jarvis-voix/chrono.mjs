// F-256 — la CHRONOLOGIE d'un passage du banc, pour comprendre un tour : sondes de la page (P), appels à la passerelle
// (A) et journal d'écoute (T), sur une seule échelle de temps (s depuis l'ouverture de la page).
//   node chrono.mjs <rapport.json> [scénario]
import { readFile } from 'node:fs/promises';

const [file, only] = process.argv.slice(2);
const report = JSON.parse(await readFile(file, 'utf8'));
const NOISE = /clips\/|\/voice$|\/face$|\/preferences|\/portrait|mail\/|notices|costs|companions|watches|\/workspaces$|\/universe|\/usage$|\/thread/;
for (const run of report.runs.filter((r) => !only || r.id === only)) {
  console.log(`=== ${run.id}`);
  const rows = [];
  for (const p of run.probes) {
    const { t, kind, ...rest } = p;
    rows.push([t, 'P', kind, JSON.stringify(rest)]);
  }
  for (const a of run.api) {
    const path = a.path.split('?')[0];
    if (path.includes('listen-trace')) {
      for (const e of a.body?.events ?? []) {
        const origin = run.traceOrigins?.[a.body?.pageId] ?? run.traceOrigin ?? run.started;
        rows.push([origin + e.t, 'T', e.ev, `${e.d ?? ''}${e.n ? ` ×${e.n}` : ''} [${e.st}/${e.md}]`]);
      }
      continue;
    }
    if (NOISE.test(path)) continue;
    const text = a.body?.text ? ` « ${String(a.body.text).slice(0, 80)} »` : '';
    const sse = a.sse.length ? ` sse=${a.sse.map((s) => s.event + (s.event === 'text' ? `:${String(s.data?.delta ?? '').slice(0, 40)}` : s.event === 'show' ? `:${s.data?.action}` : '')).join('|').slice(0, 300)}` : '';
    const timing = path.endsWith('/timings') ? ` ${JSON.stringify(a.body).slice(0, 300)}` : '';
    rows.push([a.t, 'A', `${a.method} ${path}`, `${a.status} ${a.ms ?? ''}ms${text}${sse}${timing}`]);
  }
  for (const [t, k, n, x] of rows.sort((a, b) => a[0] - b[0])) {
    console.log(`${((t - run.started) / 1000).toFixed(2).padStart(7)} ${k} ${n} ${x}`.slice(0, 400));
  }
}
