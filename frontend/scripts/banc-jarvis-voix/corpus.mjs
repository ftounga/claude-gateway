// F-256 — banc à vraie voix : fabrique le corpus SYNTHÉTIQUE (voix de synthèse ElevenLabs, déjà le fournisseur de la
// voix et de l'écoute) et indexe les vraies captures. HORS DU DÉPÔT : `$CORPUS` (défaut
// ~/.local/share/claude-gateway/jarvis-voice-corpus). La clé est lue dans l'environnement (ELEVEN), jamais écrite.
//
//   ELEVEN=… node corpus.mjs            # synthétise ce qui manque (cache : une phrase déjà faite n'est pas refaite)
//
// Sortie : `$CORPUS/synthetique/<voix>/<id>.wav` (PCM 16 bits mono 16 kHz : 300 ms de silence, les segments séparés
// par leurs pauses, 700 ms de silence) et `$CORPUS/index.json` : pour chaque enregistrement, le texte attendu, la durée
// et l'instant de FIN DE PAROLE (dernier 100 ms au-dessus du seuil), base de la mesure du premier son.
import { mkdir, readFile, writeFile, access } from 'node:fs/promises';
import { homedir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const CORPUS = process.env.CORPUS ?? join(homedir(), '.local/share/claude-gateway/jarvis-voice-corpus');
const RATE = 16000;

export function wav(pcm) {
  const header = Buffer.alloc(44);
  header.write('RIFF', 0);
  header.writeUInt32LE(36 + pcm.length, 4);
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
  header.writeUInt32LE(pcm.length, 40);
  return Buffer.concat([header, pcm]);
}

/** Le PCM d'un WAV 16 bits mono (en-tête 44 octets, le cas de nos fichiers). */
export function pcmOf(buffer) {
  const at = buffer.indexOf('data');
  return at < 0 ? buffer.subarray(44) : buffer.subarray(at + 8);
}

/**
 * Activité vocale : instants (ms) du premier et du dernier bloc de 100 ms dont l'énergie dépasse le seuil, et durée
 * active. Seuil = max(400, 8 % du bloc le plus fort) : robuste au bruit de fond léger des vraies captures.
 */
export function activity(pcm) {
  const block = RATE / 10;
  const rms = [];
  for (let i = 0; i + 1 < pcm.length; i += block * 2) {
    let sum = 0;
    let n = 0;
    for (let j = i; j + 1 < Math.min(pcm.length, i + block * 2); j += 2) {
      const v = pcm.readInt16LE(j);
      sum += v * v;
      n++;
    }
    rms.push(n ? Math.sqrt(sum / n) : 0);
  }
  const peak = Math.max(0, ...rms);
  const threshold = Math.max(400, peak * 0.08);
  const active = rms.map((r, i) => (r > threshold ? i : -1)).filter((i) => i >= 0);
  return {
    durationMs: Math.round((pcm.length / 2 / RATE) * 1000),
    speechStartMs: active.length ? active[0] * 100 : null,
    speechEndMs: active.length ? (active[active.length - 1] + 1) * 100 : null,
    activeMs: active.length * 100,
    peak: Math.round(peak),
  };
}

const silence = (ms) => Buffer.alloc(Math.round((RATE * ms) / 1000) * 2);

async function tts(voice, text, key) {
  const res = await fetch(`https://api.elevenlabs.io/v1/text-to-speech/${voice}?output_format=pcm_16000`, {
    method: 'POST',
    headers: { 'xi-api-key': key, 'Content-Type': 'application/json' },
    body: JSON.stringify({ text, model_id: 'eleven_multilingual_v2', language_code: 'fr' }),
  });
  if (!res.ok) {
    throw new Error(`TTS ${res.status}`);
  }
  return Buffer.from(await res.arrayBuffer());
}

const exists = (p) => access(p).then(() => true, () => false);

export async function build({ key = process.env.ELEVEN, corpus = CORPUS } = {}) {
  const spec = JSON.parse(await readFile(join(HERE, 'phrases.json'), 'utf8'));
  const index = { generatedAt: new Date().toISOString(), synthetic: {}, captures: {} };
  let chars = 0;
  for (const voice of spec.voices) {
    for (const phrase of spec.phrases) {
      const file = join(corpus, 'synthetique', voice.name, `${phrase.id}.wav`);
      if (!(await exists(file))) {
        if (!key) {
          throw new Error('ELEVEN absent : impossible de synthétiser ' + file);
        }
        const parts = [silence(300)];
        for (let i = 0; i < phrase.segments.length; i++) {
          chars += phrase.segments[i].length;
          parts.push(await tts(voice.id, phrase.segments[i], key));
          if (i < phrase.segments.length - 1) {
            parts.push(silence(phrase.pauses[i] ?? 800));
          }
        }
        parts.push(silence(700));
        await mkdir(dirname(file), { recursive: true });
        await writeFile(file, wav(Buffer.concat(parts)));
      }
      const pcm = pcmOf(await readFile(file));
      index.synthetic[`${voice.name}/${phrase.id}`] = { file, expected: phrase.segments.join(' '), kind: phrase.kind,
        show: !!phrase.show, ...activity(pcm) };
    }
  }
  const labels = join(corpus, 'captures-prod', 'labels.json');
  if (await exists(labels)) {
    for (const c of JSON.parse(await readFile(labels, 'utf8')).captures) {
      const file = join(corpus, 'captures-prod', `${c.id}.wav`);
      if (await exists(file)) {
        const pcm = pcmOf(await readFile(file));
        index.captures[c.id] = { file, expected: c.corrected_text || c.final_text || null, stream: c.stream_text || null,
          at: c.at, kind: c.final_text ? 'capture' : 'capture-vide', ...activity(pcm) };
      }
    }
  }
  await writeFile(join(corpus, 'index.json'), JSON.stringify(index, null, 2));
  return { index, chars };
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const { index, chars } = await build();
  console.log(`corpus : ${Object.keys(index.synthetic).length} phrases synthétiques, ${Object.keys(index.captures).length} `
    + `captures ; ${chars} caractères synthétisés ce passage (≈ ${(chars / 1000 * 0.05 * 0.92).toFixed(3)} €).`);
}
