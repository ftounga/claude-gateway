// F-256 — banc à vraie voix : le MICRO VIRTUEL et les sondes, injectés dans la page AVANT son code (banc seulement,
// rien dans le code de production).
//
// Pourquoi pas seulement `--use-file-for-fake-audio-capture` : mesuré le 2026-11-11, Chrome RELIT le fichier depuis
// le début à chaque ouverture du micro (rampe 1→20 : après fermeture puis réouverture, on réentend la seconde 1).
// Une boucle qui rouvre le micro rejouerait donc la première phrase. Ici, chaque `getUserMedia({audio})` rend une
// piste d'un même mélangeur WebAudio où le banc joue les phrases À L'HEURE où il le décide : rouvrir le micro ne
// rejoue rien, comme un vrai micro. Le reste de la boucle (VAD, flux ElevenLabs, repli batch, voix) est le vrai code.
//
// Sondes (horodatées en ms epoch, `performance.timeOrigin + performance.now()`) : ouvertures du micro, sessions
// WebSocket (hôte, ouverture, fermeture avec CODE et RAISON, messages par type), sons joués par la page (début / fin,
// clip ou phrase), et le micro lui-même (début et fin de chaque phrase jouée).
(() => {
  const now = () => performance.timeOrigin + performance.now();
  const banc = (window.__banc = { events: [], echo: 0, ctx: null, mix: null, noise: null });
  const log = (kind, data = {}) => banc.events.push({ t: Math.round(now()), kind, ...data });

  function ensure() {
    if (!banc.ctx) {
      banc.ctx = new AudioContext({ sampleRate: 48000 });
      banc.mix = banc.ctx.createMediaStreamDestination();
      banc.mix.channelCount = 1;
    }
    if (banc.ctx.state === 'suspended') {
      void banc.ctx.resume();
    }
    return banc.ctx;
  }

  // Le micro : toute demande audio rend une piste du mélangeur (la vidéo, s'il y en a, n'est pas servie).
  const md = navigator.mediaDevices;
  const original = md.getUserMedia.bind(md);
  md.getUserMedia = async (constraints) => {
    if (!constraints || !constraints.audio || window.__bancChromeMic) {
      // Témoin (`--temoin`) : le micro factice de Chrome (fichier) au lieu du mélangeur, pour vérifier que le banc
      // n'invente rien (mêmes fermetures du flux avec un « vrai » périphérique).
      if (constraints?.audio) log('mic_get', { n: 1, chrome: true });
      return original(constraints);
    }
    ensure();
    const tracks = banc.mix.stream.getAudioTracks().map((t) => t.clone());
    log('mic_get', { n: tracks.length });
    for (const track of tracks) {
      const stop = track.stop.bind(track);
      track.stop = () => { log('mic_stop'); stop(); };
    }
    return new MediaStream(tracks);
  };

  const buffers = new Map();
  async function load(url) {
    if (!buffers.has(url)) {
      const data = await (await fetch(url)).arrayBuffer();
      buffers.set(url, await ensure().decodeAudioData(data));
    }
    return buffers.get(url);
  }

  /** Joue un enregistrement dans le micro ; rend l'heure de début et de fin (ms epoch). */
  banc.play = async (url, gain = 1, id = '') => {
    const buffer = await load(url);
    const ctx = ensure();
    const source = ctx.createBufferSource();
    source.buffer = buffer;
    const g = ctx.createGain();
    g.gain.value = gain;
    source.connect(g).connect(banc.mix);
    const start = now();
    log('say_begin', { id, ms: Math.round(buffer.duration * 1000) });
    await new Promise((resolve) => {
      source.onended = resolve;
      source.start();
    });
    const end = now();
    log('say_over', { id });
    return { start: Math.round(start), end: Math.round(end) };
  };

  /** Bruit de fond continu (rose approché), au gain demandé ; 0 l'arrête. */
  banc.background = (gain) => {
    const ctx = ensure();
    if (banc.noise) {
      banc.noise.stop();
      banc.noise = null;
    }
    if (!gain) {
      return;
    }
    const length = ctx.sampleRate * 4;
    const buffer = ctx.createBuffer(1, length, ctx.sampleRate);
    const data = buffer.getChannelData(0);
    let b = 0;
    for (let i = 0; i < length; i++) {
      b = 0.97 * b + 0.03 * (Math.random() * 2 - 1);
      data[i] = b * 4;
    }
    const source = ctx.createBufferSource();
    source.buffer = buffer;
    source.loop = true;
    const g = ctx.createGain();
    g.gain.value = gain;
    source.connect(g).connect(banc.mix);
    source.start();
    banc.noise = source;
  };

  // Les sons de la page (voix de Jarvis, clips de réaction) : début réel (`playing`) et fin. `banc.echo` > 0 renvoie
  // aussi la voix dans le micro (haut-parleur entendu par le micro, sans annulation d'écho : cas le plus dur).
  const play = HTMLMediaElement.prototype.play;
  HTMLMediaElement.prototype.play = function (...args) {
    if (!this.__banc) {
      this.__banc = true;
      const kind = () => (/\/clips\//.test(this.currentSrc || this.src || '') ? 'clip' : 'voix');
      this.addEventListener('playing', () => log('sound_start', { what: kind() }));
      this.addEventListener('ended', () => log('sound_end', { what: kind(), how: 'fin' }));
      this.addEventListener('pause', () => { if (!this.ended) log('sound_end', { what: kind(), how: 'pause' }); });
      if (banc.echo > 0) {
        try {
          const ctx = ensure();
          const source = ctx.createMediaElementSource(this);
          source.connect(ctx.destination);
          const g = ctx.createGain();
          g.gain.value = banc.echo;
          source.connect(g).connect(banc.mix);
        } catch {
          // déjà relié : rien
        }
      }
    }
    return play.apply(this, args);
  };

  // Les sessions de transcription en flux : ouverture, messages par type, fermeture avec code et raison.
  const Native = window.WebSocket;
  let serial = 0;
  window.WebSocket = class extends Native {
    constructor(url, protocols) {
      super(url, protocols);
      const id = ++serial;
      const host = (() => { try { return new URL(url).host; } catch { return '?'; } })();
      const opened = now();
      log('ws_new', { id, host });
      this.addEventListener('open', () => log('ws_open', { id, ms: Math.round(now() - opened) }));
      this.addEventListener('error', () => log('ws_error', { id }));
      this.addEventListener('close', (e) => log('ws_close', { id, code: e.code, reason: String(e.reason || '').slice(0, 200),
        clean: e.wasClean, ms: Math.round(now() - opened) }));
      this.addEventListener('message', (e) => {
        try {
          const m = JSON.parse(e.data);
          const type = String(m.message_type ?? m.type ?? 'autre');
          if (type === 'partial_transcript') {
            return; // trop nombreux ; le texte retenu est dans `committed`
          }
          log('ws_msg', { id, type, text: typeof m.text === 'string' ? m.text : undefined,
            error: m.error ? String(m.error).slice(0, 200) : undefined });
        } catch {
          log('ws_msg', { id, type: 'illisible' });
        }
      });
    }
  };
})();
