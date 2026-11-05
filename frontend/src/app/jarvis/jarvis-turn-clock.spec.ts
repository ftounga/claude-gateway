import { MAX_MARK_MS, TurnClock, spokenWordCount } from './jarvis-turn-clock';

/** Le chronomètre du tour vocal (F-233 / SF-233-01). */
describe('TurnClock (F-233 / SF-233-01)', () => {
  it('range les marques depuis t0, la première occurrence seulement', () => {
    let now = 1_000;
    const clock = new TurnClock(1_000, 2_345.6, () => now);
    now = 1_200;
    clock.mark('transcript');
    now = 1_250;
    clock.mark('sent');
    now = 1_900;
    clock.mark('transcript');
    now = 5_000;
    clock.mark('firstToken');
    clock.count('falseTriggers');
    clock.count('acknowledgements', 2);
    clock.toolTurn = true;
    const report = clock.takeReport()!;
    expect(report.speechMs).toBe(2346);
    expect(report.transcriptMs).toBe(200);
    expect(report.sentMs).toBe(250);
    expect(report.firstTokenMs).toBe(4000);
    expect(report.doneMs).toBeNull();
    expect(report.falseTriggers).toBe(1);
    expect(report.acknowledgements).toBe(2);
    expect(report.toolTurn).toBeTrue();
    expect(report.listenMode).toBe('BATCH');
  });

  it('F-247 / SF-247-01 — recopie la mesure d’état de la réplique (poste éteint, question, issue)', () => {
    const clock = new TurnClock(0, null, () => 0);
    clock.fast = { brain: 'FAST', hostOffline: true, stateQuestion: true, stateAnswer: 'UNKNOWN', stateBlockMs: 2 };
    const report = clock.takeReport()!;
    expect(report.hostOffline).toBeTrue();
    expect(report.stateQuestion).toBeTrue();
    expect(report.stateAnswer).toBe('UNKNOWN');
    expect(report.stateBlockMs).toBe(2);
  });

  it('F-248 / SF-248-01 — recopie le délai premier mot → pancartes et leur nombre', () => {
    const clock = new TurnClock(0, null, () => 0);
    clock.fast = { brain: 'FAST', cardsLagMs: 140, cardsCount: 3 };
    const report = clock.takeReport()!;
    expect(report.cardsLagMs).toBe(140);
    expect(report.cardsCount).toBe(3);
  });

  it('ne se déclare qu’une fois, et écarte une marque au-delà de 10 minutes', () => {
    let now = 0;
    const clock = new TurnClock(0, null, () => now);
    now = MAX_MARK_MS + 1;
    clock.mark('done');
    expect(clock.takeReport()!.doneMs).toBeNull();
    expect(clock.takeReport()).toBeNull();
  });

  it('F-240 / SF-240-01 — ce qui est dit : mots, caractères, durée, annonce, étage le plus profond ; jamais le texte',
    () => {
      let now = 0;
      const clock = new TurnClock(0, null, () => now);
      now = 1000;
      clock.mark('firstSound');
      now = 8500;
      expect(clock.sinceMark('firstSound')).toBe(7500);
      expect(clock.sinceMark('done')).toBeNull();
      clock.said('Oui, Monsieur : le chantier 3 est terminé.', clock.sinceMark('firstSound'), { announceWords: 5 });
      expect(clock.depthReached).toBe(1);
      clock.reach(3, 'toucher');
      clock.reach(2, 'voix');
      expect(clock.depthReached).toBe(3);
      const report = clock.takeReport()!;
      expect(report.spokenWords).toBe(7);
      expect(report.spokenChars).toBe(42);
      expect(report.spokenMs).toBe(7500);
      expect(report.announceWords).toBe(5);
      expect(report.depthReached).toBe(3);
      expect(report.depthVia).toBe('toucher');
      expect(JSON.stringify(report)).not.toContain('chantier');
    });

  it('F-240 / SF-240-01 — le comptage des mots suit la règle de la passerelle', () => {
    expect(spokenWordCount(null)).toBe(0);
    expect(spokenWordCount('🎙 Oui , Monsieur : c’est fait !')).toBe(4);
    expect(spokenWordCount('l’exposition aujourd’hui dites-moi l’on-premise')).toBe(4);
    expect(spokenWordCount('4 950 caractères le 01/11 à 18:44')).toBe(7);
    expect(spokenWordCount('↪ — … ?')).toBe(0);
  });
});
