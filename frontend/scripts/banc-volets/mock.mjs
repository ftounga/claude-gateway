// Faux serveur du banc des volets : un sujet guidé, chantiers 9, 10, 11 ouverts, fils séparés.
export function createServer() {
  const WS = 'ws-1';
  const now = Date.now();
  const state = {
    focus: 10,
    open: [9, 10, 11],
    titles: { 9: 'Runner GitLab', 10: 'LDIC-323 recensement', 11: 'LDIC-338 CFT prod' },
    live: { 9: false, 10: true, 11: true },
    startedAt: { 9: now - 9000, 10: now - 5000, 11: now - 3000 },
    history: {
      9: [msg('m9a', 'USER', 'Question du chantier 9', 9), msg('m9b', 'ASSISTANT', 'Réponse du chantier 9', 9)],
      10: [msg('m10a', 'USER', 'Demande du chantier 10', 10)],
      11: [msg('m11a', 'USER', 'Demande du chantier 11', 11)],
    },
    sent: [], // { chantier, message }
    focusCalls: [],
    log: [],
  };
  function msg(id, role, content, chantier) {
    return { id, role, content, createdAt: new Date(now).toISOString(), chantier };
  }
  function chantierView(n) {
    return { number: n, title: state.titles[n], openedAt: new Date(now).toISOString(), phase: 'EXECUTION',
      phaseLabel: 'Exécution', focus: n === state.focus, planVersion: 1, validatedVersion: 1, awaitingValidation: false,
      diagnosisPending: false, closeProposed: false, executing: true, done: 1, total: 4 };
  }
  function journey(lane) {
    const shown = lane ?? state.focus;
    return { mode: 'GUIDE', phase: 'EXECUTION', phaseLabel: 'Exécution', phaseChangedAt: new Date(now).toISOString(),
      chantier: { number: shown, title: state.titles[shown], openedAt: new Date(now).toISOString() },
      closedChantiers: 0, chantiers: state.open.map(chantierView) };
  }
  function json(req, status, body) {
    req.respond({ status, contentType: 'application/json', body: JSON.stringify(body) });
  }
  function handle(req) {
    const url = new URL(req.url());
    const p = url.pathname;
    const lane = url.searchParams.get('chantier') ? Number(url.searchParams.get('chantier')) : null;
    const m = req.method();
    state.log.push(`${m} ${p}${url.search}`);
    if (p === '/api/me') {
      return json(req, 200, { id: 'u1', email: 'po@x.fr', emailVerified: true, provider: 'LOCAL', role: 'ADMIN' });
    }
    if (p === '/api/workspaces' && m === 'GET') {
      return json(req, 200, [{ id: WS, name: 'data-platform', createdAt: new Date(now).toISOString(), source: 'EMPTY',
        gitRepo: null, executionTarget: 'RUNNER', hostName: 'poste-po', hostId: 'h1' }]);
    }
    if (p === `/api/workspaces/${WS}` && m === 'GET') {
      return json(req, 200, { id: WS, name: 'data-platform', fileCount: 0, files: [], createdAt: new Date(now).toISOString(),
        source: 'EMPTY', gitRepo: null, executionTarget: 'RUNNER' });
    }
    if (p === `/api/workspaces/${WS}/journey` && m === 'GET') {
      return json(req, 200, journey(lane));
    }
    if (p === `/api/workspaces/${WS}/journey/guided-proposal/accept` && m === 'POST') {
      const n = Math.max(...state.open) + 1;
      state.open.push(n); state.titles[n] = `Nouveau ${n}`; state.focus = n; state.history[n] = [];
      state.live[n] = false; state.startedAt[n] = Date.now();
      return json(req, 200, journey(null));
    }
    const focusMatch = p.match(/\/journey\/chantiers\/(\d+)\/focus$/);
    if (focusMatch && m === 'POST') {
      state.focus = Number(focusMatch[1]);
      state.focusCalls.push(state.focus);
      return json(req, 200, journey(null));
    }
    if (p === `/api/workspaces/${WS}/chat/lanes`) {
      return json(req, 200, { lanes: state.open.map((n) => ({ chantier: n, live: state.live[n], autonomy: false,
        startedAt: state.startedAt[n] })) });
    }
    if (p === `/api/workspaces/${WS}/chat/turn` && m === 'GET') {
      const live = lane ? state.live[lane] : false;
      return json(req, 200, { live, turnId: live ? `t${lane}` : null, cursor: 0, startedAt: live ? now : null,
        pending: null });
    }
    if (p === `/api/workspaces/${WS}/chat` && m === 'GET') {
      return json(req, 200, lane ? state.history[lane] ?? [] : Object.values(state.history).flat());
    }
    if (p === `/api/workspaces/${WS}/chat/stream` && m === 'POST') {
      const body = JSON.parse(req.postData() ?? '{}');
      state.sent.push({ chantier: body.chantier ?? null, message: body.message });
      // Le flux reste « ouvert » : on ne répond jamais (le tour tourne).
      return; // pas de réponse : la requête pend
    }
    if (p === `/api/workspaces/${WS}/chat/steer` && m === 'POST') {
      const body = JSON.parse(req.postData() ?? '{}');
      state.sent.push({ chantier: lane, message: body.message, steer: true });
      return json(req, 202, { accepted: true });
    }
    if (p.includes('/chat/turn/stream') || p.includes('/chat/attach')) {
      return; // rebranchement : pend
    }
    return json(req, 404, {});
  }
  return { state, handle, WS };
}
