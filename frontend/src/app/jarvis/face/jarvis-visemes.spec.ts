import {
  Alignment, LIP_LEAD_S, VISEMES, VisemeEvent, mouthSample, numberWords, sampleAmplitude, sampleVisemes, visemeAt,
  visemesFromAlignment,
} from './jarvis-visemes';

/** Un alignement régulier : 60 ms par caractère, comme une voix réelle à débit moyen. */
function aligned(text: string, step = 0.06): Alignment {
  const chars = Array.from(text);
  return { chars, starts: chars.map((_, i) => i * step), ends: chars.map((_, i) => (i + 1) * step) };
}

const ids = (events: VisemeEvent[]): string => events.map((e) => e.v).join(' ');

describe('F-250 / SF-250-03 — les visèmes', () => {
  it('la phrase EDENRED : fermetures sur Bonjour et Monsieur, nasales, silence sur la ponctuation', () => {
    const events = visemesFromAlignment(aligned('Bonjour Monsieur, la réunion EDENRED commence.'));
    const seq = ids(events);
    // « Bonjour » : B (fermeture), on (nasale O), j, ou, r.
    expect(seq.startsWith('PBM O CHJ OU KGR')).toBeTrue();
    // « Monsieur » : M fermé, on nasal, s, i, eu, r.
    expect(seq).toContain('PBM O SZ I EU KGR');
    expect(events.filter((e) => e.v === 'SIL').length).toBe(2);
    // « commence » : mm fermé, en nasal, c doux, e muet final.
    expect(seq.endsWith('KGR O PBM A SZ SIL')).toBeTrue();
  });

  it('nasale seulement devant consonne : « réunion » finit nasal, « minutes » garde i + n', () => {
    expect(ids(visemesFromAlignment(aligned('réunion')))).toBe('KGR E U DTN I O');
    expect(ids(visemesFromAlignment(aligned('minutes')))).toBe('PBM I DTN U DTN');
  });

  it('finales muettes (s t d x z p), e muet, s entre voyelles, « oi » en deux temps, « x » en K + S', () => {
    expect(ids(visemesFromAlignment(aligned('petits')))).toBe('PBM E DTN I DTN');
    expect(ids(visemesFromAlignment(aligned('rose')))).toBe('KGR O SZ');
    expect(ids(visemesFromAlignment(aligned('moi')))).toBe('PBM OU A');
    expect(ids(visemesFromAlignment(aligned('taxi')))).toBe('DTN A KGR SZ I');
    // « oi » partage l'intervalle de ses deux lettres.
    const oi = visemesFromAlignment(aligned('oi'));
    expect(oi[0].t1).toBeCloseTo(oi[1].t0, 6);
    expect(oi[1].t1).toBeCloseTo(0.12, 6);
  });

  it('les nombres sont dits : « 10 » → dix, réparti sur ses deux caractères ; « 12 », « 71 », « 2026 »', () => {
    expect(numberWords('10')).toBe('dix');
    expect(numberWords('12')).toBe('douze');
    expect(numberWords('71')).toBe('soixanteetonze');
    expect(numberWords('80')).toBe('quatrevingt');
    expect(numberWords('2026')).toBe('deuxmillevingtsix');
    // « dix » (x final muet, h muet) occupe l'intervalle des caractères « 10 » : [0,12 ; 0,24].
    const events = visemesFromAlignment(aligned('à 10 h'));
    expect(ids(events)).toBe('A DTN I');
    const dix = events.slice(1);
    expect(dix[0].t0).toBeCloseTo(0.12, 6);
    expect(dix[dix.length - 1].t1).toBeLessThanOrEqual(0.24 + 1e-9);
  });

  it('frise triée, sans trou négatif, et texte vide ⇒ frise vide', () => {
    const events = visemesFromAlignment(aligned(' Le point EDENRED est prévu le 12 novembre. '));
    for (let k = 1; k < events.length; k++) {
      expect(events[k].t0).toBeGreaterThanOrEqual(events[k - 1].t0 - 1e-9);
      expect(events[k].t1).toBeGreaterThan(events[k].t0);
    }
    expect(visemesFromAlignment({ chars: [], starts: [], ends: [] })).toEqual([]);
  });

  it('co-articulation : la bouche se ferme vraiment sur M B P (dominance 4) et s’ouvre sur A', () => {
    const events = visemesFromAlignment(aligned('ma bonne amie', 0.08));
    const out = mouthSample();
    const m = events[0];
    sampleVisemes(events, (m.t0 + m.t1) / 2 - LIP_LEAD_S, out);
    expect(out.press).toBeGreaterThan(0.6);
    expect(out.id).toBe('PBM');
    const closed = out.open * (1 - 0.92 * out.press);
    const a = events[1];
    sampleVisemes(events, (a.t0 + a.t1) / 2 - LIP_LEAD_S, out);
    // Entre deux fermetures, le « a » s'ouvre nettement (la dominance des M B P le tempère, cadrage §3.1).
    expect(out.open * (1 - 0.92 * out.press)).toBeGreaterThan(3 * closed);
    const la = visemesFromAlignment(aligned('la', 0.08));
    sampleVisemes(la, (la[1].t0 + la[1].t1) / 2 - LIP_LEAD_S, out);
    expect(out.open).toBeGreaterThan(0.6);
  });

  it('les lèvres sont en avance de 30 ms sur le son', () => {
    const events: VisemeEvent[] = [{ v: 'A', t0: 1, t1: 1.1 }];
    const out = mouthSample();
    const peak = 1.05 - LIP_LEAD_S;
    const atPeak = sampleVisemes(events, peak, out).open;
    expect(sampleVisemes(events, peak - 0.02, out).open).toBeLessThan(atPeak);
    expect(sampleVisemes(events, peak + 0.02, out).open).toBeLessThan(atPeak);
  });

  it('silence loin de tout événement ; échantillon réutilisé (aucune allocation)', () => {
    const events = visemesFromAlignment(aligned('Oui.'));
    const out = mouthSample();
    const same = sampleVisemes(events, 30, out);
    expect(same).toBe(out);
    expect(out.open).toBeCloseTo(VISEMES.SIL.open, 3);
    expect(out.id).toBe('SIL');
  });

  it('repli amplitude : ouverture seule, bornée', () => {
    const out = sampleAmplitude(0.5, mouthSample());
    expect(out.open).toBeCloseTo(0.55, 3);
    expect(out.round).toBe(0);
    expect(sampleAmplitude(4, out).open).toBe(0.85);
    expect(sampleAmplitude(-1, out).open).toBe(0);
  });

  it('visemeAt lit la frise', () => {
    const events = visemesFromAlignment(aligned('mou'));
    expect(visemeAt(events, 0.03)).toBe('PBM');
    expect(visemeAt(events, 0.1)).toBe('OU');
    expect(visemeAt(events, 9)).toBe('SIL');
  });
});
