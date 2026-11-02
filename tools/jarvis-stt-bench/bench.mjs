#!/usr/bin/env node
/**
 * Banc d'écoute de Jarvis (F-239 / SF-239-03).
 *
 * Phrases types du PO synthétisées (ElevenLabs, voix George + une voix française), avec pauses de réflexion, puis
 * transcrites par chaque option ; mesure le taux d'erreur par mot (WER), les noms propres manqués, la latence ajoutée
 * à la fin de la parole, et le coût en €.
 *
 * Usage : ELEVEN=… OPENAI=… node bench.mjs [--only=B1,B2] [--rt] [--vad] [--clean-only] [--captures]
 *   --rt         : options en flux (Scribe v2 Realtime, commit manuel, avec/sans vocabulaire)
 *   --vad        : découpe par silence du fournisseur (0,7 / 1,0 / 1,2 / 1,5 s) — phrases coupées trop tôt
 *   --clean-only : sans la variante dégradée (réverbération, bande téléphone, Opus 12 kb/s, bruit)
 *   --captures   : rejoue AUSSI les captures réelles du PO (F-239 / SF-239-03) — CG_API (ex.
 *                  https://www.ng-itconsulting.com/api) et CG_TOKEN (jeton ADMIN du PO) ; référence = texte corrigé
 *                  par le PO s'il existe, sinon la retranscription retenue en prod (accord, pas vérité)
 * Les clés ne sont ni écrites ni journalisées. L'audio synthétisé est mis en cache dans out/audio.
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const OUT = path.join(HERE, 'out');
const AUDIO = path.join(OUT, 'audio');
fs.mkdirSync(AUDIO, { recursive: true });
const ELEVEN = process.env.ELEVEN;
const OPENAI = process.env.OPENAI;
const ARGS = process.argv.slice(2);
const ONLY = (ARGS.find((a) => a.startsWith('--only=')) ?? '').slice(7).split(',').filter(Boolean);
const SET = JSON.parse(fs.readFileSync(path.join(HERE, 'phrases.json'), 'utf8'));
const RATE = 16_000;
const USD_TO_EUR = 0.92;

// ------------------------------------------------------------------ audio

function seeded(seed) {
  let x = seed;
  return () => {
    x = (x * 1_103_515_245 + 12_345) % 2_147_483_648;
    return x / 2_147_483_648;
  };
}

async function tts(voice, text) {
  const res = await fetch(`https://api.elevenlabs.io/v1/text-to-speech/${voice}?output_format=pcm_16000`, {
    method: 'POST',
    headers: { 'xi-api-key': ELEVEN, 'content-type': 'application/json' },
    body: JSON.stringify({ text, model_id: 'eleven_multilingual_v2', language_code: 'fr' }),
  });
  if (!res.ok) {
    throw new Error(`TTS ${res.status} ${await res.text()}`);
  }
  return new Int16Array(new Uint8Array(await res.arrayBuffer()).buffer.slice(0));
}

/** Une phrase : segments synthétisés + pauses, 300 ms de calme avant, 1,6 s après ; bruit de pièce léger. */
async function sample(voice, phrase) {
  const file = path.join(AUDIO, `${phrase.id}-${voice.id}.pcm`);
  const meta = path.join(AUDIO, `${phrase.id}-${voice.id}.json`);
  if (fs.existsSync(file) && fs.existsSync(meta)) {
    return { pcm: new Int16Array(new Uint8Array(fs.readFileSync(file)).buffer), ...JSON.parse(fs.readFileSync(meta)) };
  }
  const parts = [new Int16Array(RATE * 0.3)];
  for (let i = 0; i < phrase.segments.length; i++) {
    parts.push(await tts(voice.id, phrase.segments[i]));
    if (i < phrase.pauses.length) {
      parts.push(new Int16Array(Math.round(RATE * phrase.pauses[i] / 1000)));
    }
  }
  const speechSamples = parts.reduce((n, p) => n + p.length, 0);
  parts.push(new Int16Array(RATE * 1.6));
  const total = parts.reduce((n, p) => n + p.length, 0);
  const pcm = new Int16Array(total);
  let at = 0;
  for (const p of parts) {
    pcm.set(p, at);
    at += p.length;
  }
  const rnd = seeded(phrase.id.length * 7919 + voice.id.charCodeAt(0));
  for (let i = 0; i < pcm.length; i++) {
    pcm[i] = Math.max(-32768, Math.min(32767, pcm[i] + Math.round((rnd() - 0.5) * 120)));
  }
  const speechEndMs = Math.round(speechSamples / RATE * 1000);
  fs.writeFileSync(file, Buffer.from(pcm.buffer));
  fs.writeFileSync(meta, JSON.stringify({ speechEndMs }));
  return { pcm, speechEndMs };
}

/**
 * La version « vrai micro » d'un énoncé : réverbération de pièce, bande téléphone (200-3 800 Hz), compression Opus
 * 12 kb/s (ffmpeg), puis bruit de fond à ≈ 15 dB sous la voix. Mise en cache.
 */
function degrade(s, key) {
  const file = path.join(AUDIO, `${key}-degrade.pcm`);
  if (!fs.existsSync(file)) {
    const src = path.join(AUDIO, `${key}.pcm`);
    const ogg = path.join(AUDIO, `${key}-degrade.ogg`);
    const run = (args) => {
      const r = spawnSync('ffmpeg', ['-hide_banner', '-loglevel', 'error', '-y', ...args]);
      if (r.status !== 0) throw new Error(`ffmpeg ${r.stderr}`);
    };
    run(['-f', 's16le', '-ar', '16000', '-ac', '1', '-i', src, '-af',
      'aecho=0.8:0.6:35|70|110:0.30|0.18|0.10,highpass=f=200,lowpass=f=3800', '-c:a', 'libopus', '-b:a', '12k', ogg]);
    run(['-i', ogg, '-f', 's16le', '-ar', '16000', '-ac', '1', file]);
    const pcm = new Int16Array(new Uint8Array(fs.readFileSync(file)).buffer);
    let energy = 0;
    for (const v of pcm) energy += v * v;
    const rms = Math.sqrt(energy / pcm.length);
    const rnd = seeded(key.length * 104_729);
    let brown = 0;
    for (let i = 0; i < pcm.length; i++) {
      brown = 0.98 * brown + (rnd() - 0.5);
      pcm[i] = Math.max(-32768, Math.min(32767, pcm[i] + Math.round(brown * rms * 0.18)));
    }
    fs.writeFileSync(file, Buffer.from(pcm.buffer));
  }
  return { pcm: new Int16Array(new Uint8Array(fs.readFileSync(file)).buffer), speechEndMs: s.speechEndMs };
}

function wav(pcm) {
  const header = Buffer.alloc(44);
  const data = Buffer.from(pcm.buffer, pcm.byteOffset, pcm.byteLength);
  header.write('RIFF', 0);
  header.writeUInt32LE(36 + data.length, 4);
  header.write('WAVE', 8);
  header.write('fmt ', 12);
  header.writeUInt32LE(16, 16);
  header.writeUInt16LE(1, 20);
  header.writeUInt16LE(1, 22);
  header.writeUInt32LE(RATE, 24);
  header.writeUInt32LE(RATE * 2, 28);
  header.writeUInt16LE(2, 32);
  header.writeUInt16LE(16, 34);
  header.write('data', 36);
  header.writeUInt32LE(data.length, 40);
  return Buffer.concat([header, data]);
}

/** L'énoncé seul (sans le calme final) : ce qu'une retranscription batch recevrait à la fin du tour. */
function utterance(s) {
  return s.pcm.subarray(0, Math.round((s.speechEndMs + 300) / 1000 * RATE));
}

/** Les captures réelles du compte ADMIN (WAV 16 kHz mono) : audio, et la référence disponible. */
async function captures() {
  const api = process.env.CG_API;
  const token = process.env.CG_TOKEN;
  if (!api || !token) {
    throw new Error('--captures : CG_API et CG_TOKEN sont requis');
  }
  const headers = { authorization: `Bearer ${token}` };
  const list = await (await fetch(`${api}/admin/jarvis/captures`, { headers })).json();
  const out = [];
  for (const c of list) {
    const res = await fetch(`${api}/admin/jarvis/captures/${c.id}/audio`, { headers });
    if (!res.ok) continue;
    const bytes = new Uint8Array(await res.arrayBuffer());
    const pcm = new Int16Array(bytes.buffer.slice(44));
    const ref = c.correctedText || c.finalText || '';
    out.push({ voice: { name: c.correctedText ? 'réel-corrigé' : 'réel-accord' }, phrase: { id: `c-${c.id.slice(0, 8)}`,
      pauses: [] }, variant: 'reel', ref, pcm, speechEndMs: Math.max(0, pcm.length / RATE * 1000 - 300) });
  }
  console.log(`${out.length} captures réelles`);
  return out;
}

// ------------------------------------------------------------------ WER

const HESITATIONS = new Set(['euh', 'heu', 'hum', 'hmm', 'mmh', 'ben', 'bah']);
const NUMBERS = { un: '1', une: '1', deux: '2', trois: '3', quatre: '4', cinq: '5', six: '6', sept: '7', huit: '8',
  neuf: '9', dix: '10' };

export function norm(text) {
  return text.toLowerCase().normalize('NFD').replace(/\p{M}+/gu, '').replace(/['’]/g, ' ')
    .replace(/[^a-z0-9]+/g, ' ').trim().split(' ').filter((w) => w && !HESITATIONS.has(w)).map((w) => NUMBERS[w] ?? w);
}

export function wer(ref, hyp) {
  const r = norm(ref);
  const h = norm(hyp);
  const d = Array.from({ length: r.length + 1 }, (_, i) => [i, ...new Array(h.length).fill(0)]);
  for (let j = 1; j <= h.length; j++) d[0][j] = j;
  for (let i = 1; i <= r.length; i++) {
    for (let j = 1; j <= h.length; j++) {
      d[i][j] = Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + (r[i - 1] === h[j - 1] ? 0 : 1));
    }
  }
  return { errors: d[r.length][h.length], words: r.length };
}

/** Les noms du vocabulaire présents dans la référence et absents de l'hypothèse. */
function missedNames(ref, hyp) {
  const h = ` ${norm(hyp).join(' ')} `;
  const r = ` ${norm(ref).join(' ')} `;
  return SET.vocabulary.filter((term) => {
    const t = ` ${norm(term).join(' ')} `;
    return t.trim() && r.includes(t) && !h.includes(t) && !['chantier'].includes(t.trim());
  });
}

// ------------------------------------------------------------------ options batch

async function scribeBatch(pcm, keyterms) {
  const form = new FormData();
  form.append('model_id', 'scribe_v2');
  form.append('language_code', 'fr');
  form.append('file_format', 'pcm_s16le_16');
  form.append('tag_audio_events', 'false');
  form.append('file', new Blob([Buffer.from(pcm.buffer, pcm.byteOffset, pcm.byteLength)]), 'a.pcm');
  for (const k of keyterms ?? []) {
    form.append('keyterms', k);
  }
  const res = await fetch('https://api.elevenlabs.io/v1/speech-to-text', {
    method: 'POST', headers: { 'xi-api-key': ELEVEN }, body: form,
  });
  if (!res.ok) throw new Error(`scribe ${res.status} ${(await res.text()).slice(0, 300)}`);
  return (await res.json()).text ?? '';
}

async function openaiBatch(pcm, model, { prompt, keywords } = {}) {
  const form = new FormData();
  form.append('model', model);
  form.append('file', new Blob([wav(pcm)], { type: 'audio/wav' }), 'a.wav');
  form.append('response_format', 'json');
  if (model === 'gpt-transcribe') {
    form.append('languages[]', 'fr');
    for (const k of keywords ?? []) form.append('keywords[]', k);
  } else {
    form.append('language', 'fr');
  }
  if (prompt) form.append('prompt', prompt);
  const res = await fetch('https://api.openai.com/v1/audio/transcriptions', {
    method: 'POST', headers: { authorization: `Bearer ${OPENAI}` }, body: form,
  });
  if (!res.ok) throw new Error(`${model} ${res.status} ${(await res.text()).slice(0, 300)}`);
  return (await res.json()).text ?? '';
}

const PROMPT = `Dictée d'un chef de projet à son assistant Jarvis. Clients : CAGIP, Edenred. Sujets : agenor, `
  + `data-platform, gitlab-tfstate, spider-mfa, WAF, pentest Opera, cicd-bff. Ticket LDIC-223. GitLab, Jira.`;

/** Prix en dollars par minute d'audio (pages tarifs publiques, novembre 2026). */
const BATCH = {
  B1: { label: 'Scribe v2 (batch)', usdMin: 0.22 / 60, run: (p) => scribeBatch(p) },
  B2: { label: 'Scribe v2 (batch) + vocabulaire', usdMin: 0.22 / 60 * 1.2, run: (p) => scribeBatch(p, SET.vocabulary) },
  O1: { label: 'gpt-transcribe', usdMin: 0.0045, run: (p) => openaiBatch(p, 'gpt-transcribe') },
  O2: { label: 'gpt-transcribe + vocabulaire', usdMin: 0.0045,
    run: (p) => openaiBatch(p, 'gpt-transcribe', { keywords: SET.vocabulary, prompt: PROMPT }) },
  O3: { label: 'gpt-4o-transcribe + prompt (arrêt 26/02/2027)', usdMin: 0.006,
    run: (p) => openaiBatch(p, 'gpt-4o-transcribe', { prompt: PROMPT }) },
  O4: { label: 'gpt-4o-mini-transcribe + prompt (arrêt 26/02/2027)', usdMin: 0.003,
    run: (p) => openaiBatch(p, 'gpt-4o-mini-transcribe', { prompt: PROMPT }) },
  W1: { label: 'whisper-1 (dictée actuelle)', usdMin: 0.006, run: (p) => openaiBatch(p, 'whisper-1') },
  W2: { label: 'whisper-1 + prompt', usdMin: 0.006, run: (p) => openaiBatch(p, 'whisper-1', { prompt: PROMPT }) },
};

// ------------------------------------------------------------------ flux (Scribe v2 Realtime)

async function rtToken() {
  const res = await fetch('https://api.elevenlabs.io/v1/single-use-token/realtime_scribe', {
    method: 'POST', headers: { 'xi-api-key': ELEVEN },
  });
  if (!res.ok) throw new Error(`token ${res.status}`);
  return (await res.json()).token;
}

/**
 * Joue l'audio en temps réel (morceaux de 100 ms) ; `strategy` = manual (commit à la fin de la parole + 300 ms) ou
 * vad (seuil `silence` s). Rend les segments validés avec leur instant, et la latence du dernier commit.
 */
async function realtime(s, opts) {
  for (let attempt = 0; ; attempt++) {
    try {
      return await realtimeOnce(s, opts);
    } catch (e) {
      if (attempt >= 2) {
        console.error(`RT abandon : ${e.message}`);
        return { commits: [], commitSentAt: 0, speechEndAt: 0, error: String(e.message) };
      }
      await new Promise((r) => setTimeout(r, 3_000));
    }
  }
}

async function realtimeOnce(s, { strategy, silence = 0.7, keyterms = null }) {
  const token = await rtToken();
  let url = `wss://api.elevenlabs.io/v1/speech-to-text/realtime?model_id=scribe_v2_realtime&audio_format=pcm_16000`
    + `&language_code=fr&commit_strategy=${strategy}&vad_silence_threshold_secs=${silence.toFixed(2)}`;
  for (const k of keyterms ?? []) url += `&keyterms=${encodeURIComponent(k)}`;
  url += `&token=${encodeURIComponent(token)}`;
  const ws = new WebSocket(url);
  const commits = [];
  let commitSentAt = 0;
  const t0 = Date.now();
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('start timeout')), 5_000);
    ws.onmessage = (e) => {
      const m = JSON.parse(e.data);
      if (m.message_type === 'session_started') {
        clearTimeout(timer);
        resolve();
      } else if (m.message_type?.startsWith('committed_transcript') && m.message_type === 'committed_transcript') {
        commits.push({ text: m.text, at: Date.now() - t0 });
      } else if (/error|exceeded|invalid/.test(m.message_type ?? '')) {
        console.error('RT', m.message_type, m.error ?? m.message ?? '');
      }
    };
    ws.onerror = () => reject(new Error('ws error'));
    ws.onclose = (e) => reject(new Error(`ws close ${e.code} ${e.reason}`));
  });
  ws.onclose = null;
  const start = Date.now();
  const chunk = RATE / 10;
  const endSample = Math.round((s.speechEndMs + 300) / 1000 * RATE);
  for (let i = 0; i < s.pcm.length; i += chunk) {
    const part = s.pcm.subarray(i, Math.min(s.pcm.length, i + chunk));
    ws.send(JSON.stringify({ message_type: 'input_audio_chunk', commit: false, sample_rate: RATE,
      audio_base_64: Buffer.from(part.buffer, part.byteOffset, part.byteLength).toString('base64') }));
    if (strategy === 'manual' && i + chunk >= endSample && !commitSentAt) {
      commitSentAt = Date.now() - t0;
      ws.send(JSON.stringify({ message_type: 'input_audio_chunk', commit: true, sample_rate: RATE, audio_base_64: '' }));
    }
    const due = start + ((i + chunk) / RATE) * 1000;
    await new Promise((r) => setTimeout(r, Math.max(0, due - Date.now())));
  }
  await new Promise((r) => setTimeout(r, 1_500));
  ws.close();
  const speechEndAt = start - t0 + s.speechEndMs;
  return { commits, commitSentAt, speechEndAt };
}

// ------------------------------------------------------------------ campagne

const RESULTS = path.join(OUT, `results-${Date.now()}.json`);

/** Écrit les résultats au fil de l'eau : un passage interrompu garde ce qui est mesuré. */
function save(results) {
  fs.writeFileSync(RESULTS, JSON.stringify(results, null, 2));
}

function pct(values, p) {
  const v = values.filter((x) => x !== null && Number.isFinite(x)).sort((a, b) => a - b);
  if (v.length === 0) return null;
  return v[Math.min(v.length - 1, Math.floor(p * (v.length - 1) + 0.5))];
}

async function main() {
  const samples = [];
  for (const voice of SET.voices) {
    for (const phrase of SET.phrases) {
      const clean = await sample(voice, phrase);
      samples.push({ voice, phrase, variant: 'propre', ref: phrase.segments.join(' '), ...clean });
      if (!ARGS.includes('--clean-only')) {
        samples.push({ voice, phrase, variant: 'degrade', ref: phrase.segments.join(' '),
          ...degrade(clean, `${phrase.id}-${voice.id}`) });
      }
    }
  }
  if (ARGS.includes('--captures')) {
    samples.push(...await captures());
  }
  const results = { generatedAt: new Date().toISOString(), samples: samples.length, options: {} };
  const keep = (id) => ONLY.length === 0 || ONLY.includes(id);

  for (const [id, opt] of Object.entries(BATCH)) {
    if (!keep(id)) continue;
    const rows = [];
    for (const s of samples) {
      const pcm = utterance(s);
      const started = performance.now();
      let hyp = '';
      let error = null;
      try {
        hyp = await opt.run(pcm);
      } catch (e) {
        error = String(e.message ?? e);
      }
      rows.push({ id: `${s.phrase.id}/${s.voice.name}/${s.variant}`, variant: s.variant, ref: s.ref, hyp, error,
        ms: Math.round(performance.now() - started), audioS: pcm.length / RATE, ...wer(s.ref, hyp),
        missed: missedNames(s.ref, hyp) });
      process.stdout.write('.');
    }
    results.options[id] = summarize(id, opt.label, rows, opt.usdMin);
    save(results);
    console.log(`\n${id} ${opt.label}: WER ${results.options[id].werPct} % ${JSON.stringify(results.options[id].byVariant)}, noms manqués ${results.options[id].missedNames}, `
      + `latence p50 ${results.options[id].p50Ms} ms / p90 ${results.options[id].p90Ms} ms`);
  }

  if (ARGS.includes('--rt')) {
    for (const [id, cfg] of [['RT0', { label: 'Scribe v2 Realtime (prod, sans vocabulaire), commit manuel' }],
      ['RT1', { label: 'Scribe v2 Realtime + vocabulaire (keyterms), commit manuel', keyterms: SET.vocabulary }]]) {
      if (!keep(id)) continue;
      const rows = [];
      for (const s of samples) {
        const r = await realtime(s, { strategy: 'manual', keyterms: cfg.keyterms });
        const hyp = r.commits.map((c) => c.text).join(' ');
        const last = r.commits[r.commits.length - 1];
        rows.push({ id: `${s.phrase.id}/${s.voice.name}/${s.variant}`, variant: s.variant, ref: s.ref, hyp, ms: last ? last.at - r.commitSentAt : null,
          ...wer(s.ref, hyp), missed: missedNames(s.ref, hyp) });
        process.stdout.write('.');
      }
      results.options[id] = summarize(id, cfg.label, rows, 0.39 / 60 * (cfg.keyterms ? 1.2 : 1));
      save(results);
      console.log(`\n${id}: WER ${results.options[id].werPct} % ${JSON.stringify(results.options[id].byVariant)}, noms manqués ${results.options[id].missedNames}, `
        + `commit→texte p50 ${results.options[id].p50Ms} ms`);
    }
  }

  if (ARGS.includes('--vad')) {
    results.vad = {};
    for (const silence of [0.7, 1.0, 1.2, 1.5]) {
      const rows = [];
      for (const s of samples.filter((x) => x.phrase.pauses.length > 0)) {
        const r = await realtime(s, { strategy: 'vad', silence });
        const first = r.commits[0];
        const cut = r.commits.length > 1 || (first && first.at < r.speechEndAt);
        rows.push({ id: `${s.phrase.id}/${s.voice.name}/${s.variant}`, variant: s.variant, ref: s.ref, commits: r.commits.map((c) => c.text),
          firstText: first?.text ?? '', cut, endToCommitMs: r.commits.length ? r.commits[r.commits.length - 1].at
            - r.speechEndAt : null, firstWer: wer(s.ref, first?.text ?? '') });
        process.stdout.write('.');
      }
      const cutCount = rows.filter((r) => r.cut).length;
      results.vad[silence] = {
        phrasesWithPause: rows.length, cutTooEarly: cutCount,
        firstSegmentWerPct: +(100 * rows.reduce((n, r) => n + r.firstWer.errors, 0)
          / rows.reduce((n, r) => n + r.firstWer.words, 0)).toFixed(1),
        endToCommitP50Ms: pct(rows.map((r) => r.endToCommitMs), 0.5), rows,
      };
      save(results);
      console.log(`\nVAD ${silence}s : ${cutCount}/${rows.length} phrases coupées, fin→commit p50 `
        + `${results.vad[silence].endToCommitP50Ms} ms`);
    }
  }

  save(results);
  console.log(`\nRésultats : ${RESULTS}`);
}

function summarize(id, label, rows, usdMin) {
  const errors = rows.reduce((n, r) => n + r.errors, 0);
  const words = rows.reduce((n, r) => n + r.words, 0);
  const byVariant = {};
  for (const v of ['propre', 'degrade', 'reel']) {
    const sub = rows.filter((r) => r.variant === v);
    if (sub.length) {
      byVariant[v] = { werPct: +(100 * sub.reduce((n, r) => n + r.errors, 0) / sub.reduce((n, r) => n + r.words, 0))
        .toFixed(1), missedNames: sub.reduce((n, r) => n + r.missed.length, 0), cases: sub.length };
    }
  }
  // Usage soutenu (cadrage F-233) : 3 000 énoncés/mois de 5 s en moyenne ⇒ 250 min/mois.
  return {
    label, werPct: +(100 * errors / words).toFixed(1), errors, words, byVariant,
    missedNames: rows.reduce((n, r) => n + r.missed.length, 0),
    failed: rows.filter((r) => r.error).length,
    p50Ms: pct(rows.map((r) => r.ms), 0.5), p90Ms: pct(rows.map((r) => r.ms), 0.9),
    eurPerMonth: +(usdMin * 250 * USD_TO_EUR).toFixed(2), rows,
  };
}

// Lancé directement (pas importé par relecture.mjs).
if (process.argv[1] === fileURLToPath(import.meta.url)) {
  main().catch((e) => {
    console.error(e.message);
    process.exit(1);
  });
}
