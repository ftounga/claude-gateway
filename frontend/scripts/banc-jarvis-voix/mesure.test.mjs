// F-256 — tests de la mesure et de la porte du banc à vraie voix (aucun réseau) : `node --test mesure.test.mjs`.
import { test } from 'node:test';
import assert from 'node:assert/strict';

import { activity, pcmOf, wav } from './corpus.mjs';
import { analyse, analyseTurn, percentile, summary, traceEvents, wer, words } from './mesure.mjs';
import { gate } from './porte.mjs';

test('words : sans accents, ponctuation ni hésitations', () => {
  assert.deepEqual(words('Euh, côté WAF chez EDENRED ?'), ['cote', 'waf', 'chez', 'edenred']);
});

test('wer : 0 pour un texte identique au signe près, 1 pour un texte vide, null sans référence', () => {
  assert.equal(wer('Montre-moi les sujets de CAGIP.', 'montre moi les sujets de cagip'), 0);
  assert.equal(wer('Oui.', ''), 1);
  assert.equal(wer('a b c d', 'a x c d'), 0.25);
  assert.equal(wer(null, 'texte'), null);
});

test('percentile : médiane et p90, valeurs absentes ignorées', () => {
  assert.equal(percentile([3, 1, 2, null], 50), 2);
  assert.equal(percentile([1, 2, 3, 4, 5, 6, 7, 8, 9, 10], 90), 9);
  assert.equal(percentile([], 50), null);
});

test('activity : début et fin de parole d\'un enregistrement', () => {
  const tone = Buffer.alloc(16000 * 2); // 1 s de son
  for (let i = 0; i < 16000; i++) tone.writeInt16LE(Math.round(8000 * Math.sin(i / 3)), i * 2);
  const pcm = Buffer.concat([Buffer.alloc(16000), tone, Buffer.alloc(16000)]); // 0,5 s + 1 s + 0,5 s
  const a = activity(pcmOf(wav(pcm)));
  assert.equal(a.durationMs, 2000);
  assert.equal(a.speechStartMs, 500);
  assert.equal(a.speechEndMs, 1500);
  assert.equal(activity(Buffer.alloc(3200)).speechEndMs, null);
});

const sse = (events) => events.map(([event, data]) => ({ t: 0, event, data }));

function turnFixture() {
  const turn = { id: 'n05', kind: 'ecran', after: 'ecoute', expected: 'Montre-moi les sujets de CAGIP.', start: 1000,
    end: 4000, speechEnd: 3000 };
  const api = [
    { t: 3500, method: 'POST', path: '/api/jarvis/converse', body: { text: 'Montre moi', speculationId: 's1' },
      sse: sse([['dropped', {}]]) },
    { t: 4200, method: 'POST', path: '/api/jarvis/converse', body: { text: 'Montre-moi les sujets de CAGIP.' },
      sse: sse([['show', { action: 'univers' }], ['text', { delta: 'Voici les sujets de CAGIP.' }], ['done', {}]]) },
    { t: 5000, method: 'POST', path: '/api/jarvis/speech', body: { text: 'Voici les sujets de CAGIP.' }, sse: [] },
    { t: 9000, method: 'POST', path: '/api/jarvis/timings', sse: [],
      body: { listenMode: 'CONV_STREAM', streamFallback: 'coupe', sttProvider: 'elevenlabs-batch', firstSoundMs: 2600 } },
    { t: 9500, method: 'POST', path: '/api/jarvis/listen-trace', sse: [],
      body: { events: [{ seq: 1, t: 4500, ev: 'show', d: 'univers' }, { seq: 2, t: 20000, ev: 'show', d: 'hors' }] } },
  ];
  const probes = [
    { t: 3600, kind: 'ws_close', code: 1000, reason: 'queue_overflow' },
    { t: 5600, kind: 'sound_start', what: 'voix' },
  ];
  return { turn, api, probes };
}

test('analyseTurn : entendu, spéculation jetée, repli, premier son, affichage, fenêtre du tour', () => {
  const { turn, api, probes } = turnFixture();
  const traces = traceEvents(api, 0);
  const a = analyseTurn(turn, { api, probes, traces, next: 12000, spokenBefore: new Set() });
  assert.equal(a.heard, true);
  assert.equal(a.heardText, 'Montre-moi les sujets de CAGIP.');
  assert.equal(a.wer, 0);
  assert.equal(a.speculative, 1);
  assert.equal(a.streamFallback, 'coupe');
  assert.deepEqual(a.ws.closes, [{ code: 1000, reason: 'queue_overflow', ms: undefined }]);
  assert.equal(a.firstSoundMs, 2600);
  assert.deepEqual(a.showAsked, ['univers']);
  assert.deepEqual(a.shown, ['univers']); // l'affichage à 20 s est hors de la fenêtre
  assert.equal(a.voiciWithoutShow, false);
  assert.deepEqual(a.repeated, []);
});

test('analyseTurn : « Voici » sans affichage, réponse déjà dite, phrase perdue', () => {
  const { turn, api, probes } = turnFixture();
  const noShow = api.filter((e) => !e.path.includes('listen-trace'));
  const said = new Set(['voici les sujets de cagip']);
  const a = analyseTurn(turn, { api: noShow, probes, traces: [], next: 12000, spokenBefore: said });
  assert.equal(a.voiciWithoutShow, true);
  assert.deepEqual(a.repeated, ['voici les sujets de cagip']);
  const lost = analyseTurn(turn, { api: [], probes: [], traces: [], next: 12000, spokenBefore: new Set() });
  assert.equal(lost.heard, false);
  assert.equal(lost.firstSoundMs, null);
});

test('analyse + porte : taux entendu, replis comptés, porte non franchie, résumé lisible', () => {
  const { turn, api, probes } = turnFixture();
  const run = { id: 's', label: 'Scénario', ready: true, pageErrors: [], ended: 12000, traceOrigin: 0, probes, api,
    turns: [turn, { ...turn, id: 'n06', expected: 'Allons-y.', start: 12000, end: 13000, speechEnd: 12800 }] };
  const report = analyse([{ ...run, ended: 20000 }], { before: { today: { total: 1.5 } }, after: { today: { total: 1.62 } } });
  assert.equal(report.totals.phrases, 2);
  assert.equal(report.totals.heard, 1);
  assert.equal(report.totals.heardRate, 50);
  assert.equal(report.totals.fallbacks, 1);
  assert.equal(report.totals.costEur, 0.12);
  const g = gate(report);
  assert.equal(g.pass, false);
  assert.ok(g.failures.some((f) => f.startsWith('entendu 50')));
  assert.ok(g.failures.some((f) => f.includes('repli')));
  report.gate = g;
  assert.match(summary(report), /Phrases entendues {5}: 1\/2 \(50 %\)/);
});

test('porte : franchie quand tout est au vert', () => {
  const totals = { heardRate: 100, firstSoundMedianMs: 1500, repeated: [], voiciWithoutShow: [], fallbacks: 0,
    fallbackKinds: {} };
  assert.deepEqual(gate({ totals }).failures, []);
});
