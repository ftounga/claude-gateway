import { FastBrainHandlers, FastBrainOutcome, FastDone, JarvisFastBrainClient } from './jarvis-fast-brain';
import { JarvisSpeculation, normalizeUtterance, sameUtterance } from './jarvis-speculation';

describe('sameUtterance (F-252 / SF-252-02, CA5)', () => {
  it('ignore casse, espaces et ponctuation', () => {
    expect(sameUtterance('Bonjour,  Jarvis.', 'bonjour jarvis')).toBeTrue();
    expect(sameUtterance('C’est prêt ', "c'est prêt")).toBeTrue();
  });
  it('« ? » compte, où qu’il soit collé', () => {
    expect(sameUtterance('ça avance', 'ça avance ?')).toBeFalse();
    expect(sameUtterance('ça avance?', 'ça avance ?')).toBeTrue();
  });
  it('accents et chiffres comptent', () => {
    expect(sameUtterance('le chantier 12', 'le chantier 13')).toBeFalse();
    expect(sameUtterance('il a été', 'il a ete')).toBeFalse();
  });
  it('un texte vide reste vide', () => {
    expect(normalizeUtterance(' ... ')).toBe('');
  });
});

describe('JarvisSpeculation (F-252 / SF-252-02, CA6)', () => {
  type Fake = jasmine.SpyObj<JarvisFastBrainClient>;
  let client: Fake;
  let upstream: FastBrainHandlers;
  let signal: AbortSignal | undefined;
  let finish: (o: FastBrainOutcome) => void;
  const body = { subjectId: 's1', text: 'Où en est PROJ-123 ?', screen: 'x', presentation: false };
  const done = { brain: 'FAST', spokenText: 'En revue.', firstTokenMs: 900, model: 'm' } as FastDone;

  function recorder() {
    const log: string[] = [];
    const handlers: FastBrainHandlers = {
      onText: (s) => log.push(`text:${s}`),
      onUnderstood: (s) => log.push(`understood:${s}`),
      onDelegated: () => log.push('delegated'),
      onFallback: () => log.push('fallback'),
      onDone: () => log.push('done'),
    };
    return { log, handlers };
  }

  beforeEach(() => {
    client = jasmine.createSpyObj<JarvisFastBrainClient>('client', ['converse', 'verdict']);
    client.verdict.and.resolveTo();
    client.converse.and.callFake((_b, h, s) => {
      upstream = h;
      signal = s;
      return new Promise<FastBrainOutcome>((resolve) => { finish = resolve; });
    });
  });

  it('rien n’atteint l’écran avant l’adoption ; puis rejeu dans l’ordre et flux direct', async () => {
    const spec = new JarvisSpeculation(client, body, 'id-1');
    expect(client.converse.calls.mostRecent().args[0].speculationId).toBe('id-1');
    const { log, handlers } = recorder();
    upstream.onUnderstood?.('Où en est PROJ-123 ?');
    upstream.onText('PROJ-123 est en revue.');
    expect(log).toEqual([]);
    const outcome = spec.adopt('où en est proj-123 ?', handlers);
    expect(client.verdict).toHaveBeenCalledOnceWith('id-1', true, 'où en est proj-123 ?');
    expect(log).toEqual(['understood:Où en est PROJ-123 ?', 'text:PROJ-123 est en revue.']);
    upstream.onDone(done);
    expect(log[2]).toBe('done');
    finish('ok');
    expect(await outcome).toBe('ok');
  });

  it('à l’abandon : appel interrompu, verdict négatif, rien de joué', () => {
    const spec = new JarvisSpeculation(client, body, 'id-2');
    upstream.onText('Une phrase.');
    spec.drop();
    upstream.onText('Une autre.');
    expect(signal?.aborted).toBeTrue();
    expect(client.verdict).toHaveBeenCalledOnceWith('id-2', false, null);
    spec.drop();
    expect(client.verdict).toHaveBeenCalledTimes(1);
  });

  it('une réponse provisoire ne vaut que pour le même texte et la même requête', () => {
    const spec = new JarvisSpeculation(client, body, 'id-3');
    expect(spec.matches('où en est proj-123 ?', body)).toBeTrue();
    expect(spec.matches('où en est proj-124 ?', body)).toBeFalse();
    expect(spec.matches('où en est proj-123 ?', { ...body, screen: 'autre' })).toBeFalse();
    expect(spec.matches('où en est proj-123 ?', { ...body, subjectId: 's2' })).toBeFalse();
  });
});
