import { MAX_MARK_MS, TurnClock } from './jarvis-turn-clock';

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

  it('ne se déclare qu’une fois, et écarte une marque au-delà de 10 minutes', () => {
    let now = 0;
    const clock = new TurnClock(0, null, () => now);
    now = MAX_MARK_MS + 1;
    clock.mark('done');
    expect(clock.takeReport()!.doneMs).toBeNull();
    expect(clock.takeReport()).toBeNull();
  });
});
