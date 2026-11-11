// F-256 — banc à vraie voix : la MESURE, tour par tour, à partir de ce que le banc a observé (fonctions pures,
// testées par `mesure.test.mjs`). Un tour = une phrase jouée dans le micro ; sa fenêtre court de son début au début de
// la phrase suivante (ou à la fin du scénario). Toutes les heures sont en ms epoch.

/** Mots comparables : minuscules, sans accents ni ponctuation ; hésitations retirées. */
export function words(text) {
  return String(text ?? '').toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '')
    .replace(/[^a-z0-9' -]/g, ' ').replace(/['-]/g, ' ').split(/\s+/)
    .filter((w) => w && !['euh', 'heu', 'hum', 'ben', 'bah'].includes(w));
}

/** Taux d'erreur de mots (Levenshtein sur les mots) ; `null` sans référence. */
export function wer(reference, hypothesis) {
  const r = words(reference);
  const h = words(hypothesis);
  if (r.length === 0) {
    return null;
  }
  const d = Array.from({ length: r.length + 1 }, (_, i) => [i, ...Array(h.length).fill(0)]);
  for (let j = 1; j <= h.length; j++) d[0][j] = j;
  for (let i = 1; i <= r.length; i++) {
    for (let j = 1; j <= h.length; j++) {
      d[i][j] = Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + (r[i - 1] === h[j - 1] ? 0 : 1));
    }
  }
  return Math.round((d[r.length][h.length] / r.length) * 1000) / 1000;
}

export function percentile(values, p) {
  const v = values.filter((x) => typeof x === 'number' && Number.isFinite(x)).sort((a, b) => a - b);
  if (v.length === 0) {
    return null;
  }
  const i = Math.min(v.length - 1, Math.max(0, Math.ceil((p / 100) * v.length) - 1));
  return v[i];
}

/** Une phrase dite, comparable (pour les répétitions). */
export function sentenceKey(text) {
  return words(text).join(' ');
}

/** Le texte dit par une réponse SSE du cerveau rapide (deltas `text`). */
function sseText(entry) {
  return entry.sse.filter((e) => e.event === 'text').map((e) => e.data?.delta ?? e.data?.text ?? '').join('');
}

const isConverse = (e) => e.method === 'POST' && /^\/api\/jarvis\/converse(\?|$)/.test(e.path);
const isTiming = (e) => e.method === 'POST' && /^\/api\/jarvis\/timings/.test(e.path);
const isSpeech = (e) => e.method === 'POST' && /^\/api\/jarvis\/speech/.test(e.path);
const isTrace = (e) => e.method === 'POST' && /^\/api\/jarvis\/listen-trace/.test(e.path);
const isOpusSend = (e) => e.method === 'POST' && /^\/api\/atelier\//.test(e.path) && e.body && typeof e.body.text === 'string';

/**
 * Les événements du journal d'écoute, remis à l'heure epoch. `origins` : l'ouverture du journal, un nombre, ou par page
 * (`pageId` → ms epoch) quand la page s'est rechargée en cours de scénario.
 */
export function traceEvents(api, origins) {
  const originOf = (pageId) => (typeof origins === 'number' ? origins : origins?.[pageId] ?? null);
  return api.filter(isTrace).flatMap((e) => (e.body?.events ?? []).map((ev) => {
    const origin = originOf(e.body?.pageId);
    return { ...ev, page: e.body?.pageId ?? null, at: origin === null ? null : origin + ev.t };
  }));
}

/** Analyse un tour. `next` = début du tour suivant (ou fin du scénario). */
export function analyseTurn(turn, { api, probes, traces, next, spokenBefore }) {
  const inWindow = (t) => t >= turn.start && t < next;
  const converses = api.filter((e) => isConverse(e) && inWindow(e.t));
  const kept = converses.filter((e) => !e.sse.some((s) => s.event === 'dropped'));
  const final = kept.at(-1) ?? null;
  const opus = api.filter((e) => isOpusSend(e) && inWindow(e.t));
  const heardText = final?.body?.text ?? opus.at(-1)?.body?.text ?? null;
  const timings = api.filter((e) => isTiming(e) && inWindow(e.t)).map((e) => e.body).filter(Boolean);
  const timing = timings.at(-1) ?? null;
  const ws = probes.filter((p) => p.kind === 'ws_close' && inWindow(p.t))
    .map((p) => ({ code: p.code, reason: p.reason, ms: p.ms }));
  const wsOpened = probes.filter((p) => p.kind === 'ws_open' && inWindow(p.t)).length;
  const committed = probes.filter((p) => p.kind === 'ws_msg' && p.type === 'committed_transcript' && inWindow(p.t))
    .map((p) => p.text).filter(Boolean);
  const wsErrors = probes.filter((p) => p.kind === 'ws_msg' && p.error && inWindow(p.t)).map((p) => `${p.type}:${p.error}`);
  const sounds = probes.filter((p) => p.kind === 'sound_start' && inWindow(p.t) && (turn.speechEnd === null || p.t >= turn.speechEnd));
  const firstSound = sounds[0] ?? null;
  const firstVoice = sounds.find((s) => s.what === 'voix') ?? null;
  const spoken = api.filter((e) => isSpeech(e) && inWindow(e.t)).map((e) => e.body?.text).filter(Boolean);
  const said = spoken.length ? spoken : converses.map(sseText).filter(Boolean);
  const keys = said.map(sentenceKey).filter((k) => k.split(' ').length >= 3);
  const repeated = keys.filter((k, i) => keys.indexOf(k) !== i || spokenBefore.has(k));
  const shows = (final?.sse ?? []).filter((s) => s.event === 'show').map((s) => s.data?.action ?? '?');
  const tr = traces.filter((t) => t.at !== null && inWindow(t.at));
  const shown = tr.filter((t) => t.ev === 'show').map((t) => t.d ?? '');
  const refused = tr.filter((t) => t.ev === 'show_refused').map((t) => t.d ?? '');
  const rejected = tr.filter((t) => t.ev === 'speech_rejected').map((t) => t.d ?? '');
  const voici = said.some((s) => /\b(voici|voil[aà])\b/i.test(s));
  const fallback = timing?.streamFallback ?? null;
  // Une phrase peut être entendue puis traitée sur place, sans cerveau (réponse à un entretien, au budget, une
  // correction) : l'écran note alors `transcript chars=N` ; le texte est celui que le flux a validé.
  const transcribed = tr.some((t) => t.ev === 'transcript' && /chars=[1-9]/.test(t.d ?? ''));
  const local = heardText === null && transcribed;
  const text = heardText ?? (local ? committed.at(-1) ?? '' : null);
  return {
    id: turn.id, kind: turn.kind, after: turn.after, expected: turn.expected, note: turn.note ?? null,
    heard: text !== null,
    heardText: text,
    handledLocally: local,
    wer: turn.expected && text !== null ? wer(turn.expected, text) : null,
    speculative: converses.length - kept.length,
    listenMode: timing?.listenMode ?? null,
    streamFallback: fallback,
    sttProvider: timing?.sttProvider ?? null,
    ws: { opened: wsOpened, closes: ws, errors: wsErrors, committed },
    brain: timing?.brain ?? (final ? 'FAST' : opus.length ? 'OPUS' : null),
    delegated: !!final?.sse.some((s) => s.event === 'delegated') || opus.length > 0,
    firstSoundMs: firstSound && turn.speechEnd !== null ? firstSound.t - turn.speechEnd : null,
    firstSoundWhat: firstSound?.what ?? null,
    firstVoiceMs: firstVoice && turn.speechEnd !== null ? firstVoice.t - turn.speechEnd : null,
    reportedFirstSoundMs: timing?.firstSoundMs ?? null,
    said,
    repeated,
    showAsked: shows,
    shown,
    refused,
    voiciWithoutShow: voici && shown.length === 0,
    rejected,
  };
}

/** Analyse tous les scénarios ; `costs` = coûts Jarvis du jour avant/après (`GET /api/jarvis/costs`). */
export function analyse(runs, costs = {}) {
  const scenarios = runs.map((run) => {
    const traces = traceEvents(run.api, run.traceOrigins ?? run.traceOrigin ?? null);
    const spokenBefore = new Set();
    const turns = run.turns.filter((t) => t.start).map((turn, i, all) => {
      const next = all[i + 1]?.start ?? run.ended;
      const a = analyseTurn(turn, { api: run.api, probes: run.probes, traces, next, spokenBefore });
      a.said.map(sentenceKey).forEach((k) => spokenBefore.add(k));
      return a;
    });
    return { id: run.id, label: run.label, ready: run.ready, pageErrors: run.pageErrors, turns,
      missedListening: run.turns.filter((t) => t.note === 'jarvis_n_ecoutait_pas').length,
      // Rechargements réels de la page APRÈS l'ouverture (un document neuf = `page_open` ; le premier est celui du banc).
      reloads: Math.max(0, run.probes.filter((p) => p.kind === 'page_open').length - 1),
      reloadCauses: traces.filter((t) => /reload|appupdate/.test(t.ev)).map((t) => t.ev) };
  });
  const all = scenarios.flatMap((s) => s.turns.map((t) => ({ ...t, scenario: s.id })));
  const expected = all.filter((t) => t.expected);
  const heard = expected.filter((t) => t.heard);
  const empties = all.filter((t) => !t.expected);
  const fallbacks = all.filter((t) => t.streamFallback);
  const unexplained = fallbacks.filter((t) => !t.ws.closes.some((c) => c.reason || ![1000, 1005, 1006].includes(c.code)));
  const latencies = heard.map((t) => t.firstSoundMs);
  const voice = heard.map((t) => t.firstVoiceMs);
  const total = (c) => Number(c?.today?.total ?? NaN);
  const costEur = Number.isFinite(total(costs.after) - total(costs.before))
    ? Math.round((total(costs.after) - total(costs.before)) * 10000) / 10000 : null;
  return {
    scenarios,
    totals: {
      phrases: expected.length,
      heard: heard.length,
      heardRate: expected.length ? Math.round((heard.length / expected.length) * 1000) / 10 : null,
      lost: expected.filter((t) => !t.heard).map((t) => `${t.scenario}/${t.id}`),
      // Compris : entendu ET transcrit à au plus un mot sur quatre près (une phrase tronquée est entendue, pas comprise).
      understood: heard.filter((t) => t.wer !== null && t.wer <= 0.25).length,
      understoodRate: expected.length
        ? Math.round((heard.filter((t) => t.wer !== null && t.wer <= 0.25).length / expected.length) * 1000) / 10 : null,
      werMedian: percentile(heard.map((t) => t.wer), 50),
      werP90: percentile(heard.map((t) => t.wer), 90),
      firstSoundMedianMs: percentile(latencies, 50),
      firstSoundP90Ms: percentile(latencies, 90),
      firstVoiceMedianMs: percentile(voice, 50),
      firstVoiceP90Ms: percentile(voice, 90),
      streamTurns: all.filter((t) => /STREAM/.test(t.listenMode ?? '')).length,
      batchTurns: all.filter((t) => /BATCH/.test(t.listenMode ?? '')).length,
      fallbacks: fallbacks.length,
      fallbackKinds: count(fallbacks.map((t) => t.streamFallback)),
      fallbacksUnexplained: unexplained.length,
      wsCloseCodes: count(all.flatMap((t) => t.ws.closes.map((c) => `${c.code}${c.reason ? ` ${c.reason}` : ''}`))),
      repeated: all.filter((t) => t.repeated.length).map((t) => `${t.scenario}/${t.id}`),
      voiciWithoutShow: all.filter((t) => t.voiciWithoutShow).map((t) => `${t.scenario}/${t.id}`),
      showAsked: all.filter((t) => t.showAsked.length).length,
      shown: all.filter((t) => t.shown.length).length,
      showRefused: all.filter((t) => t.refused.length).map((t) => `${t.scenario}/${t.id}: ${t.refused.join(',')}`),
      emptyCaptures: empties.length,
      emptyCapturesAnswered: empties.filter((t) => t.heard).map((t) => `${t.id}: ${t.heardText}`),
      delegated: all.filter((t) => t.delegated).length,
      notListening: scenarios.reduce((n, s) => n + s.missedListening, 0),
      reloads: scenarios.reduce((n, s) => n + s.reloads, 0),
      reloadCauses: scenarios.flatMap((s) => s.reloadCauses),
      costEur,
    },
  };
}

function count(list) {
  return list.reduce((acc, k) => ({ ...acc, [k]: (acc[k] ?? 0) + 1 }), {});
}

const ms = (v) => (v === null || v === undefined ? '—' : `${(v / 1000).toFixed(2)} s`);

/** Le résumé lisible, comparable d'une version à l'autre. */
export function summary(report) {
  const t = report.totals;
  const lines = [
    `BANC À VRAIE VOIX — ${report.meta?.at ?? ''} ${report.meta?.commit ? `(${report.meta.commit})` : ''}`.trim()
      + (report.meta?.loop ? ` — boucle ${report.meta.loop}` : '') + (report.meta?.voice ? `, voix ${report.meta.voice}` : ''),
    `Porte : ${report.gate?.pass ? 'FRANCHIE' : 'NON FRANCHIE'}${report.gate?.failures?.length ? ` — ${report.gate.failures.join(' ; ')}` : ''}`,
    '',
    `Phrases entendues     : ${t.heard}/${t.phrases} (${t.heardRate ?? '—'} %)${t.lost.length ? ` — perdues : ${t.lost.join(', ')}` : ''}`,
    `Phrases comprises     : ${t.understood}/${t.phrases} (${t.understoodRate ?? '—'} %) — entendues et transcrites à ≤ 25 % de mots près`,
    `Écart de transcription: WER médian ${t.werMedian ?? '—'}, p90 ${t.werP90 ?? '—'}`,
    `Premier son           : médiane ${ms(t.firstSoundMedianMs)}, p90 ${ms(t.firstSoundP90Ms)} (voix : ${ms(t.firstVoiceMedianMs)} / ${ms(t.firstVoiceP90Ms)})`,
    `Écoute                : ${t.streamTurns} tours en flux, ${t.batchTurns} en batch ; replis ${t.fallbacks} ${JSON.stringify(t.fallbackKinds)}, non expliqués ${t.fallbacksUnexplained}`,
    `Fermetures du flux    : ${JSON.stringify(t.wsCloseCodes)}`,
    `Réponses répétées     : ${t.repeated.length}${t.repeated.length ? ` (${t.repeated.join(', ')})` : ''}`,
    `« Voici » sans image  : ${t.voiciWithoutShow.length}${t.voiciWithoutShow.length ? ` (${t.voiciWithoutShow.join(', ')})` : ''}`,
    `Écran                 : ${t.showAsked} ordres demandés, ${t.shown} tours affichés, refus : ${t.showRefused.length ? t.showRefused.join(' ; ') : 'aucun'}`,
    `Captures vides        : ${t.emptyCaptures} jouées, ${t.emptyCapturesAnswered.length} ont donné un tour${t.emptyCapturesAnswered.length ? ` (${t.emptyCapturesAnswered.join(' | ')})` : ''}`,
    `Délégations à Opus    : ${t.delegated}`,
    `Jarvis n'écoutait pas : ${t.notListening} fois au moment de jouer une phrase (40 s d'attente)`,
    `Page rechargée        : ${t.reloads} fois en plein scénario${t.reloadCauses.length ? ` (${t.reloadCauses.join(', ')})` : ''}`,
    `Coût du passage       : ${t.costEur === null ? '—' : `${t.costEur.toFixed(4)} €`} (compteur Jarvis de la passerelle)`,
    '',
  ];
  for (const s of report.scenarios) {
    lines.push(`— ${s.label}${s.ready ? '' : ' [Jarvis n’a pas démarré l’écoute]'}${s.pageErrors.length ? ` [erreurs : ${s.pageErrors.length}]` : ''}`);
    for (const turn of s.turns) {
      lines.push(`  ${turn.id.padEnd(9).slice(0, 9)} ${turn.heard ? 'entendu' : 'PERDU  '} ${(turn.listenMode ?? '—').padEnd(11)}`
        + ` ${turn.streamFallback ? `repli=${turn.streamFallback} ` : ''}son ${ms(turn.firstSoundMs)}`
        + ` wer ${turn.wer ?? '—'} « ${(turn.heardText ?? '').slice(0, 70)} »`
        + `${turn.handledLocally ? ' (traité sur place, sans cerveau)' : ''}${turn.repeated.length ? ' RÉPÉTÉ' : ''}${turn.voiciWithoutShow ? ' VOICI-SANS-IMAGE' : ''}`
        + `${turn.rejected.length ? ` rejet=${turn.rejected.join(',')}` : ''}`);
    }
  }
  return lines.join('\n');
}
