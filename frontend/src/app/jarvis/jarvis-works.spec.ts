import {
  CourseWatch, JarvisWork, courseEndPhrase, matchWorks, stopTarget,
  ArmedConfirmations, CONFIRM_TTL_MS, asksLaunch, confirmationVerdict, isOtherRequest, names, newWorkId, shortName, whichQuestion,
} from './jarvis-works';

describe('F-270 / SF-270-02 — les confirmations rattachées à leur travail', () => {
  let now = 0;
  const armed = () => new ArmedConfirmations(() => now);
  const ldic = { workId: 'd-1', subjectId: 'w-1', chantier: 4, name: 'LDIC-325' };
  const agenor = { workId: 'd-2', subjectId: 'w-2', chantier: null, name: 'agenor' };

  beforeEach(() => now = 1_000_000);

  it('une seule armée : « oui » la vise ; aucune : rien', () => {
    const a = armed();
    expect(a.target('oui').kind).toBe('none');
    a.arm(ldic);
    const t = a.target('oui');
    expect(t.kind).toBe('one');
    expect(t.kind === 'one' && t.item.name).toBe('LDIC-325');
  });

  it('deux armées : ambiguë sans nom, la nommée sinon (« ldic 325 » vaut « LDIC-325 »)', () => {
    const a = armed();
    a.arm(ldic);
    a.arm(agenor);
    expect(a.target('oui').kind).toBe('ambiguous');
    const t = a.target('oui pour ldic 325');
    expect(t.kind === 'one' && t.item.workId).toBe('d-1');
    expect(whichQuestion(a.live())).toBe('LDIC-325 ou agenor ?');
  });

  it('une par fil : la plus récente remplace ; expirée après 2 min ; désarmée', () => {
    const a = armed();
    a.arm(ldic);
    a.arm({ ...ldic, workId: 'd-3' });
    expect(a.live().map((i) => i.workId)).toEqual(['d-3']);
    now += CONFIRM_TTL_MS + 1;
    expect(a.size).toBe(0);
    a.arm(agenor);
    a.disarm(a.live()[0]);
    expect(a.size).toBe(0);
  });

  it('F-271 / SF-271-03 — une question de lancement expirée est rendue une fois ; remplacée ou désarmée, jamais', () => {
    const a = armed();
    a.arm({ ...ldic, launch: true });
    a.arm({ ...agenor, launch: false });
    now += CONFIRM_TTL_MS + 1;
    expect(a.takeExpiredLaunches().map((i) => i.name)).toEqual(['LDIC-325']);
    expect(a.takeExpiredLaunches()).toEqual([]);
    a.arm({ ...ldic, launch: true });
    a.arm({ ...ldic, workId: 'd-9', launch: false }); // remplacée sur le même fil
    a.disarm(a.live()[0]);
    now += CONFIRM_TTL_MS + 1;
    expect(a.takeExpiredLaunches()).toEqual([]);
    expect(asksLaunch('Je le lance en autonome. Vous confirmez ?')).toBeTrue();
    expect(asksLaunch('Je valide le plan v1. Vous confirmez ?')).toBeFalse();
  });

  it('le verdict ignore le nom visé ; une négation reste un doute', () => {
    const items = [{ ...ldic, at: 0 }, { ...agenor, at: 0 }];
    expect(confirmationVerdict('Oui pour LDIC-325', items)).toBe('oui');
    expect(confirmationVerdict('oui, agenor', items)).toBe('oui');
    expect(confirmationVerdict('non', items)).toBe('non');
    expect(confirmationVerdict('oui mais pas agenor', items)).toBe('doute');
  });

  it('une phrase de quatre mots ou plus est une autre demande', () => {
    expect(isOtherRequest('Montre-moi mes rendez-vous de demain')).toBeTrue();
    expect(isOtherRequest('euh attends')).toBeFalse();
  });

  it('nom court : clé de ticket, chantier, sujet', () => {
    expect(shortName('Lancer LDIC-325', 4, 'data-platform')).toBe('LDIC-325');
    expect(shortName('le backlog', 4, 'data-platform')).toBe('chantier 4');
    expect(shortName('le backlog', null, 'data-platform')).toBe('data-platform');
    expect(names('arrête le chantier 4', 'chantier 4')).toBeTrue();
    expect(names('arrête agenorix', 'agenor')).toBeFalse();
    expect(newWorkId()).toMatch(/^[0-9a-f-]{36}$/);
  });

  it('F-270 / SF-270-04 — « arrête facturation » vise un nom ; « arrête tout », « arrête de parler » non', () => {
    expect(stopTarget('Arrête facturation.')).toBe('facturation');
    expect(stopTarget('Jarvis, stop LDIC-325')).toBe('ldic 325');
    expect(stopTarget('Arrête le chantier 4')).toBe('chantier 4');
    expect(stopTarget('Arrête tout')).toBeNull();
    expect(stopTarget('Arrête ça')).toBeNull();
    expect(stopTarget('Arrête CAGIP')).toBe('cagip');
    expect(stopTarget('Arrête de parler')).toBeNull();
    expect(stopTarget('stop')).toBeNull();
    const works = [{ name: 'LDIC-325', label: 'Lancer LDIC-325', subjectName: 'data-platform' },
      { name: 'agenor', label: 'tickets', subjectName: 'agenor' }];
    expect(matchWorks('ldic 325', works).map((w) => w.name)).toEqual(['LDIC-325']);
    expect(matchWorks('data platform', works).map((w) => w.name)).toEqual(['LDIC-325']);
    expect(matchWorks('facturation', works)).toEqual([]);
  });
});

describe('F-271 / SF-271-04 — la course parle', () => {
  const course = (status: string, extra: Partial<JarvisWork> = {}, c: Record<string, unknown> = {}): JarvisWork => ({
    id: 'course:1', origin: 'COURSE', subjectId: 'w-1', subjectName: 'data-platform', chantier: 13, name: 'LDIC-325',
    label: 'LDIC-325 — topic SNS', phase: status === 'RUNNING' ? 'EXECUTING' : status === 'DONE' ? 'DONE'
      : status === 'STOPPED' ? 'CANCELLED' : 'FAILED', since: '2026-11-21T08:00:00Z',
    endedAt: status === 'RUNNING' ? null : '2026-11-21T08:10:00Z',
    course: { stepsDone: 3, stepsTotal: 6, status: status as never, reason: null, byUser: false, ...c }, ...extra,
  });

  it('fin, blocage (raison dite), échec, arrêt, jusqu’au blocage ; rien en cours ni sur une reprise en main', () => {
    expect(courseEndPhrase(course('DONE'))).toBe('LDIC-325 est fini.');
    expect(courseEndPhrase(course('BLOCKED', {}, { reason: 'Il manque le droit IAM sur le compte data-ingestion dev.' })))
      .toBe('LDIC-325 est bloqué : il manque le droit IAM sur le compte data-ingestion dev.');
    expect(courseEndPhrase(course('FAILED'))).toBe('LDIC-325 s’est arrêté en échec.');
    expect(courseEndPhrase(course('STOPPED'))).toBe('LDIC-325 est arrêté, trois étapes sur six.');
    expect(courseEndPhrase(course('PARTIAL'))).toBe('LDIC-325 a avancé jusqu’au blocage : trois étapes sur six.');
    expect(courseEndPhrase(course('RUNNING'))).toBeNull();
    expect(courseEndPhrase(course('STOPPED', {}, { byUser: true }))).toBeNull();
    expect(courseEndPhrase({ ...course('DONE'), origin: 'DELEGATION' })).toBeNull();
  });

  it('une course vue en cours puis finie est rendue une fois ; finie au premier regard, jamais', () => {
    const w = new CourseWatch();
    expect(w.ended([course('DONE', { id: 'course:old' })])).toEqual([]);
    expect(w.ended([course('RUNNING')])).toEqual([]);
    expect(w.ended([course('DONE')]).map((x) => x.id)).toEqual(['course:1']);
    expect(w.ended([course('DONE')])).toEqual([]);
  });
});
