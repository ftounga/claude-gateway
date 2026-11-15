import {
  AnnouncementQueue, FastBrainHandlers, announcementText, dispatch, isCorrection, isRepeatCommand,
} from './jarvis-fast-brain';

/** F-235 / SF-235-05 — le client du cerveau rapide : événements, file d'annonces, « redis-moi ». */
describe('Le cerveau rapide, côté écran (F-235 / SF-235-05)', () => {
  function recorder(): { handlers: FastBrainHandlers; log: string[] } {
    const log: string[] = [];
    return {
      log,
      handlers: {
        onText: (s) => log.push(`text:${s}`),
        onUnderstood: (s) => log.push(`understood:${s}`),
        onDelegated: (d) => log.push(`delegated:${d.label}:${d.announce}`),
        onFallback: (r) => log.push(`fallback:${r}`),
        onDone: (d) => log.push(`done:${d.brain}`),
      },
    };
  }

  it('relaie text, delegated, fallback et done ; signale done et error', () => {
    const { handlers, log } = recorder();
    expect(dispatch('event:text\ndata:{"delta":"Le chantier en est aux tests. "}', handlers)).toBeNull();
    expect(dispatch('event:delegated\ndata:{"label":"tickets","announce":"Je regarde les tickets."}', handlers))
      .toBeNull();
    expect(dispatch('event:fallback\ndata:{"reason":"budget"}', handlers)).toBeNull();
    expect(dispatch('event:done\ndata:{"brain":"FAST"}', handlers)).toBe('done');
    expect(dispatch('event:error\ndata:{"error":"x"}', handlers)).toBe('error');
    expect(dispatch('event:text\ndata:pas du json', handlers)).toBeNull();
    expect(log).toEqual(['text:Le chantier en est aux tests.', 'delegated:tickets:Je regarde les tickets.',
      'fallback:budget', 'done:FAST']);
  });

  it('la file d’annonces : FIFO, rien quand le moment n’est pas libre, la dernière reste à redire', () => {
    const queue = new AnnouncementQueue();
    queue.push({ label: 'premier', phrase: 'A.', subjectId: 'w', confirm: false });
    queue.push({ label: 'second', phrase: 'B.', subjectId: 'w', confirm: false });
    expect(queue.next(false)).toBeNull();
    expect(queue.next(true)?.label).toBe('premier');
    expect(queue.repeat()?.label).toBe('premier');
    expect(queue.next(true)?.label).toBe('second');
    expect(queue.next(true)).toBeNull();
    expect(queue.repeat()?.label).toBe('second');
  });

  it('une annonce commence par son label', () => {
    expect(announcementText({ label: 'tickets bloqués.', phrase: 'Deux sont bloqués.', subjectId: 'w', confirm: false }))
      .toBe('Pour tickets bloqués : Deux sont bloqués.');
    expect(announcementText({ label: '', phrase: 'C’est fait.', subjectId: 'w', confirm: false })).toBe('C’est fait.');
  });

  it('F-235 / SF-235-07 — une correction est une liste fermée', () => {
    expect(isCorrection('Non, c’est faux.')).toBeTrue();
    expect(isCorrection('Vérifie')).toBeTrue();
    expect(isCorrection('Jarvis, demande à Opus')).toBeTrue();
    expect(isCorrection('Non')).toBeTrue();
    expect(isCorrection('Non merci, ça ira')).toBeFalse();
    expect(isCorrection('Vérifie les tickets d’AGENOR')).toBeFalse();
  });

  it('« redis-moi » est une liste fermée', () => {
    expect(isRepeatCommand('Redis-moi')).toBeTrue();
    expect(isRepeatCommand('Jarvis, répète')).toBeTrue();
    expect(isRepeatCommand('Tu peux répéter ?')).toBeTrue();
    expect(isRepeatCommand('Redis-moi ce que fait le chantier 3')).toBeFalse();
  });

  it('F-239 / SF-239-05 — l’événement « understood » porte la parole relue', () => {
    const { handlers, log } = recorder();
    expect(dispatch('event:understood\ndata:{"text":"Où en est CAGIP ?"}', handlers)).toBeNull();
    expect(dispatch('event:understood\ndata:{"text":"  "}', handlers)).toBeNull();
    expect(log).toEqual(['understood:Où en est CAGIP ?']);
  });
});

/** F-257 / SF-257-13 — le préfixe chauffé pendant la parole. */
describe('JarvisFastBrainClient.warmTurn (F-257 / SF-257-13)', () => {
  it('demande le pré-chauffage du tour (sujet, présentation), sans attendre ni lever', async () => {
    const { TestBed } = await import('@angular/core/testing');
    const { AuthService } = await import('../core/services/auth.service');
    const { JarvisFastBrainClient } = await import('./jarvis-fast-brain');
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ providers: [{ provide: AuthService, useValue: { token: () => 't' } }] });
    const client = TestBed.inject(JarvisFastBrainClient);
    const fetchSpy = spyOn(window, 'fetch').and.returnValue(Promise.reject(new Error('réseau')));
    client.warmTurn('S1', false);
    client.warmTurn(null, true);
    expect(fetchSpy.calls.count()).toBe(2);
    const [url, init] = fetchSpy.calls.argsFor(0) as [string, RequestInit];
    expect(url).toBe('/api/jarvis/converse/warm');
    expect(JSON.parse(init.body as string)).toEqual({ subjectId: 'S1', turn: true, presentation: false });
    expect(JSON.parse((fetchSpy.calls.argsFor(1)[1] as RequestInit).body as string))
      .toEqual({ turn: true, presentation: true });
    await Promise.resolve();
  });
});
