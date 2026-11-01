import {
  AnnouncementQueue, FastBrainHandlers, announcementText, dispatch, isRepeatCommand,
} from './jarvis-fast-brain';

/** F-235 / SF-235-05 — le client du cerveau rapide : événements, file d'annonces, « redis-moi ». */
describe('Le cerveau rapide, côté écran (F-235 / SF-235-05)', () => {
  function recorder(): { handlers: FastBrainHandlers; log: string[] } {
    const log: string[] = [];
    return {
      log,
      handlers: {
        onText: (s) => log.push(`text:${s}`),
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

  it('« redis-moi » est une liste fermée', () => {
    expect(isRepeatCommand('Redis-moi')).toBeTrue();
    expect(isRepeatCommand('Jarvis, répète')).toBeTrue();
    expect(isRepeatCommand('Tu peux répéter ?')).toBeTrue();
    expect(isRepeatCommand('Redis-moi ce que fait le chantier 3')).toBeFalse();
  });
});
