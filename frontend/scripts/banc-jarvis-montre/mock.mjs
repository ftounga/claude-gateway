// F-253 — faux serveur du banc « Jarvis montre vraiment » : l'univers du PO (forme réelle, données inventées), les
// pièces d'un sujet, et un cerveau rapide scripté (SSE `text` / `show` / `cards` / `done`) par phrase reçue.
// Aucune donnée réelle, aucun réseau. Chaque requête /api est journalisée (`state.log`).
export const CAGIP = 'c-cagip';
export const DATA_PLATFORM = 'ws-data-platform';
export const AGENOR = 'ws-agenor';
export const HELIOS = 'ws-helios';

const now = Date.now();
const iso = (ms) => new Date(ms).toISOString();

function chantier(number, title, done, total, focus = false) {
  return { number, title, phase: 'EXECUTION', phaseLabel: 'Exécution', done, total,
    step: { rank: done + 1, title: `Étape ${done + 1}` }, focus, live: false, waitingOn: [] };
}

export const UNIVERSE = {
  generatedAt: iso(now),
  toSendCount: 2,
  clients: [
    { id: CAGIP, name: 'CAGIP', virtual: false, connected: false, missionStatus: 'ACTIVE', truncated: false,
      toSend: ['Point Loki'], people: [{ name: 'Killian', variants: ['Kilian'], role: 'tech lead' }],
      subjects: [
        { id: DATA_PLATFORM, name: 'data-platform', hostTerminal: false, live: false,
          chantiers: [chantier(3, 'Cours vidéo Kubernetes', 5, 8)] },
        { id: AGENOR, name: 'agenor', hostTerminal: false, live: false,
          chantiers: [chantier(6, 'Logs ca-savings vers Loki', 11, 16, true), chantier(7, 'VPC qua/preprod/prod', 18, 19)] },
      ] },
    { id: 'c-edenred', name: 'Edenred', virtual: false, connected: false, missionStatus: 'ACTIVE', truncated: false,
      toSend: ['Relance Helios'], people: [],
      subjects: [{ id: HELIOS, name: 'helios', hostTerminal: false, live: false,
        chantiers: [chantier(2, 'Migration Helios', 2, 9)] }] },
  ],
};

function video(n, title) {
  return { id: `video:v${n}`, category: 'PRODUCTION', kind: 'VIDEO', title, caption: `Chapitre ${n}`,
    captionSource: 'TITLE', at: iso(now - n * 3600_000), subjectId: DATA_PLATFORM, chantier: 3,
    chantierLink: 'MESSAGE', thumb: null, open: null, meta: { durationSeconds: 120 }, available: 'OK' };
}
export const PIECES = { [DATA_PLATFORM]: Array.from({ length: 6 }, (_, i) => video(i + 1, `Kubernetes — leçon ${i + 1}`)) };

/**
 * Le script du cerveau rapide : pour chaque phrase (sous-chaîne, minuscule), les événements à émettre. Par défaut,
 * le comportement PROUVÉ en prod le 07/11 (un ordre d'écran inapplicable puis « Voici. »).
 */
export const SCRIPTS = [];

export function createServer() {
  const state = { log: [], converse: [], usage: [] };
  const json = (req, status, body) => req.respond({ status, contentType: 'application/json',
    body: JSON.stringify(body) });
  function sse(req, events) {
    const body = events.map(([name, data]) => `event:${name}\ndata:${JSON.stringify(data)}\n\n`).join('');
    req.respond({ status: 200, contentType: 'text/event-stream', body });
  }
  function handle(req) {
    const url = new URL(req.url());
    const p = url.pathname;
    const m = req.method();
    state.log.push(`${m} ${p}${url.search}`);
    if (p === '/api/jarvis/usage') {
      state.usage.push(JSON.parse(req.postData() ?? '{}'));
      return req.respond({ status: 204 });
    }
    if (p === '/api/jarvis/calendar/events') {
      const at = (h) => { const d = new Date(now); d.setHours(h, 0, 0, 0); return d.toISOString(); };
      return json(req, 200, { from: url.searchParams.get('from'), to: url.searchParams.get('to'), updatedAt: iso(now), feeds: 1,
        items: [{ id: 'e1', feedLabel: 'Pro', uid: 'u1', start: at(11), end: at(12), allDay: false, title: 'Revue Zahi', location: '', status: 'CONFIRMED' },
          { id: 'e2', feedLabel: 'Pro', uid: 'u2', start: at(15), end: at(16), allDay: false, title: 'Point Loki', location: '', status: 'CONFIRMED' }] });
    }
    if (p === '/api/me') {
      return json(req, 200, { id: 'u1', email: 'po@x.fr', emailVerified: true, provider: 'LOCAL', role: 'ADMIN' });
    }
    if (p === '/api/workspaces' && m === 'GET') {
      return json(req, 200, UNIVERSE.clients.flatMap((c) => c.subjects.map((s) => ({ id: s.id, name: s.name,
        createdAt: iso(now), source: 'EMPTY', gitRepo: null, executionTarget: 'RUNNER', hostName: 'poste-po',
        hostId: c.id, clientId: c.id }))));
    }
    if (p === '/api/jarvis/universe') {
      return json(req, 200, UNIVERSE);
    }
    if (p === '/api/jarvis/pieces') {
      const subject = url.searchParams.get('subjectId');
      const client = url.searchParams.get('clientId');
      const kind = url.searchParams.get('kind');
      const source = client === CAGIP ? Object.values(PIECES).flat() : (PIECES[subject] ?? []);
      const list = source.filter((x) => !kind || x.kind === kind);
      return json(req, client || (subject && PIECES[subject] !== undefined) ? 200 : 404,
        { pieces: list, total: list.length, truncated: false });
    }
    if (p === '/api/jarvis/converse/warm') {
      return req.respond({ status: 204 });
    }
    if (p === '/api/jarvis/converse') {
      const body = JSON.parse(req.postData() ?? '{}');
      state.converse.push(body);
      const said = String(body.text ?? '').toLowerCase();
      const script = SCRIPTS.find((s) => said.includes(s.when));
      const events = script ? script.events : [['text', { delta: 'Pardon, Monsieur. ' }]];
      return sse(req, [...events, ['done', { brain: 'FAST', spokenText: '', firstTokenMs: 400, model: 'banc',
        turnSeq: null, guards: null, budget: null, portrait: false, state: null }]]);
    }
    return json(req, 404, { error: 'banc' });
  }
  return { state, handle };
}
