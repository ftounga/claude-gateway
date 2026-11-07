import { TestBed } from '@angular/core/testing';

import {
  JarvisListenTrace, JarvisTraceSink, TRACE_BATCH_SIZE, TRACE_MAX_EVENTS, TraceBatch, errorName, pageVersion,
  traceDetail,
} from './jarvis-listen-trace';

describe('JarvisListenTrace (F-233 / SF-233-15)', () => {
  let batches: TraceBatch[];
  let trace: JarvisListenTrace;
  let state: string;

  beforeEach(() => {
    batches = [];
    state = 'repos';
    TestBed.configureTestingModule({
      providers: [{ provide: JarvisTraceSink, useValue: { send: (b: TraceBatch) => batches.push(b) } }],
    });
    trace = TestBed.inject(JarvisListenTrace);
    trace.start({ companion: true, state: () => state, mode: () => 'active', version: 'ABC123XY' });
  });

  afterEach(() => trace.stop());

  it('range état, mode, numéro et temps ; un lot part à 20 événements', () => {
    for (let i = 0; i < TRACE_BATCH_SIZE - 1; i++) {
      trace.mark(`e${i}`);
    }
    expect(batches.length).toBe(0);
    trace.mark('dernier');
    expect(batches.length).toBe(1);
    const batch = batches[0];
    expect(batch.companion).toBeTrue();
    expect(batch.version).toBe('ABC123XY');
    expect(batch.events.length).toBe(TRACE_BATCH_SIZE);
    expect(batch.events[0]).toEqual(jasmine.objectContaining({ seq: 1, ev: 'e0', st: 'repos', md: 'active' }));
  });

  it('un même événement répété garde une ligne et compte ses répétitions', () => {
    trace.mark('listen_silence', 'peak=0.00');
    trace.mark('listen_silence', 'peak=0.00');
    trace.mark('listen_silence', 'peak=0.00');
    trace.flush();
    expect(batches[0].events.length).toBe(1);
    expect(batches[0].events[0].n).toBe(3);
  });

  it('une erreur ou un arrêt part tout de suite', () => {
    trace.mark('mic_fail', 'NotReadableError');
    expect(batches.length).toBe(1);
    trace.mark('conv_pause', 'micro');
    expect(batches.length).toBe(2);
  });

  it('nom invalide ignoré ; au plus 1 500 événements par page', () => {
    trace.mark('Pas Valide');
    trace.flush();
    expect(batches.length).toBe(0);
    for (let i = 0; i < TRACE_MAX_EVENTS + 50; i++) {
      trace.mark(`x${i % 2}`);
    }
    trace.flush();
    expect(batches.flatMap((b) => b.events).length).toBe(TRACE_MAX_EVENTS);
  });

  it('un état hors liste est vidé (jamais de texte libre)', () => {
    state = 'Bonjour Jarvis';
    trace.mark('state');
    trace.flush();
    expect(batches[0].events[0].st).toBe('');
  });

  it('détail : liste blanche et 120 caractères ; erreur : son nom seulement', () => {
    expect(traceDetail('a "b" <c é')).toBe('a b c');
    expect(traceDetail('x'.repeat(200))!.length).toBe(120);
    expect(traceDetail(null)).toBeUndefined();
    expect(errorName(new DOMException('secret message', 'NotAllowedError'))).toBe('NotAllowedError');
    expect(errorName('micro-refuse')).toBe('micro-refuse');
    expect(errorName({})).toBe('inconnue');
  });

  it('la version de la page : l’empreinte du main-*.js servi', () => {
    const doc = { querySelectorAll: () => [{ getAttribute: () => 'main-TJGIBB4X.js' }] } as unknown as Document;
    expect(pageVersion(doc)).toBe('TJGIBB4X');
    expect(pageVersion({ querySelectorAll: () => [] } as unknown as Document)).toBeNull();
  });

  it('la sortie de l’écran envoie ce qui attend', () => {
    trace.mark('state');
    trace.stop();
    expect(batches.flatMap((b) => b.events).map((e) => e.ev)).toEqual(['state', 'screen_exit']);
  });
});
