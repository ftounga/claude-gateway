import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';

import { TerminalSituationComponent } from './terminal-situation.component';
import {
  Situation,
  readLastVisit,
  recordVisit,
  situationIdleHours,
  situationOpensOnEntry,
} from '../../core/models/situation.models';
import { findPanelCommand } from './slash-panel-commands';

const SITUATION: Situation = {
  generatedAt: '2026-10-31T10:00:00Z',
  // « Dernier échange » se calcule à l'écran depuis lastActivityAt (SF-225-03) : relatif à maintenant.
  lastActivityAt: new Date(Date.now() - 6 * 3_600_000 - 60_000).toISOString(),
  idleHours: 6,
  mode: 'GUIDE',
  chantiers: [{ number: 2, title: 'Logs', phaseLabel: 'Exécution', focus: true, done: 1, total: 3, nextRank: 2,
    nextStep: 'Installer Fluent Bit', planAwaitingValidation: false, diagnosisPending: false,
    who: 'L’outil peut avancer', whoDetail: null, stale: false }],
  attentes: [{ id: 'a1', description: 'Compte forge', status: 'DEMANDE', qui: 'TIERS', to: 'Zahi',
    since: '2026-10-29T09:00:00Z', chantierNumber: 2 }],
  envois: [
    { messageId: 'm1', blockIndex: 0, kind: 'MAIL', subject: 'Compte forge', recipient: 'Zahi',
      proposedAt: '2026-10-31T04:00:00Z', sentAt: null, attenteKey: 'acces-forge', attenteStatus: 'A_FAIRE',
      excerpt: 'Bonjour Zahi, peux-tu…' },
    { messageId: 'm1', blockIndex: 1, kind: 'MESSAGE', subject: null, recipient: 'l’équipe',
      proposedAt: '2026-10-31T04:00:00Z', sentAt: '2026-10-31T05:00:00Z', attenteKey: null, attenteStatus: null,
      excerpt: 'Salut' },
  ],
  exchange: { request: 'Prépare le mail pour Zahi', requestAt: null,
    reply: '<<essentiel>>Le mail est prêt.<</essentiel>>\n\nDétail…', replyAt: null },
  pieces: [{ id: 'd1', path: '.atelier/entrees/capture.png', sizeBytes: 2048, createdAt: null, messageId: null }],
  suites: [{ title: 'Relancer Zahi', request: 'Relance Zahi' }],
};

/** F-225 / SF-225-02 — la carte « Point de situation ». */
describe('F-225 / SF-225-02 + SF-225-03 — le point de situation', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideNoopAnimations()],
    });
    http = TestBed.inject(HttpTestingController);
    try {
      localStorage.removeItem('cg.situation.idleHours');
    } catch {
      // sans stockage
    }
  });

  afterEach(() => http.verify());

  function mount(readOnly = false) {
    const fixture = TestBed.createComponent(TerminalSituationComponent);
    fixture.componentRef.setInput('workspaceId', 'ws-1');
    fixture.componentRef.setInput('readOnly', readOnly);
    fixture.detectChanges();
    http.expectOne('/api/workspaces/ws-1/situation').flush(SITUATION);
    fixture.detectChanges();
    return fixture;
  }

  it('dit où on en était : chantier et étape, mail pas marqué envoyé, attente, essentiel, pièces, suites', () => {
    const el: HTMLElement = mount().nativeElement;
    const text = el.textContent ?? '';
    expect(text).toContain('Dernier échange il y a 6 h');
    expect(text).toContain('Chantier 2 — Logs');
    expect(text).toContain('Ensuite : 2. Installer Fluent Bit');
    expect(el.querySelector('.sit__item--pending')?.textContent).toContain('Mail à Zahi');
    expect(el.querySelector('.sit__item--pending')?.textContent).toContain('pas marqué envoyé');
    expect(text).toContain('Message à l’équipe : envoyé');
    expect(text).toContain('Compte forge');
    expect(text).toContain('Le mail est prêt.');
    expect(text).not.toContain('<<essentiel>>');
    expect(text).toContain('capture.png');
    expect(text).toContain('Relancer Zahi');
  });

  it('SF-227-03 : « Ce qui attend » est groupé par chantier, puis au fil de l’eau', () => {
    const fixture = TestBed.createComponent(TerminalSituationComponent);
    fixture.componentRef.setInput('workspaceId', 'ws-1');
    fixture.detectChanges();
    http.expectOne('/api/workspaces/ws-1/situation').flush({ ...SITUATION, attentes: [
      ...SITUATION.attentes,
      { id: 'a2', description: 'Relire la doc', status: 'A_FAIRE', qui: 'MOI', to: null, since: null, chantierNumber: null },
      { id: 'a3', description: 'Ancien chantier', status: 'A_FAIRE', qui: null, to: null, since: null, chantierNumber: 7 },
    ] });
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    const groups = Array.from(el.querySelectorAll('.sit__sub')).map((h) => h.textContent?.trim());
    expect(groups).toEqual(['Chantier 2 — Logs', 'Au fil de l’eau']);
    const lists = el.querySelectorAll('[aria-label="Ce qui attend"] .sit__list');
    expect(lists[0].textContent).toContain('Compte forge');
    expect(lists[0].textContent).toContain('demandée');
    expect(lists[1].textContent).toContain('Relire la doc');
    expect(lists[1].textContent).toContain('Ancien chantier');
  });

  it('[Voir] montre le texte, [Marquer envoyé] fait le geste et met la carte à jour, [Annuler] le défait', () => {
    const fixture = mount();
    const el: HTMLElement = fixture.nativeElement;
    let changed = 0;
    fixture.componentInstance.sendsChanged.subscribe(() => changed++);
    (el.querySelector('.sit__see') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(el.querySelector('.sit__excerpt')?.textContent).toContain('Objet : Compte forge — Bonjour Zahi');
    (el.querySelector('.sit__mark') as HTMLButtonElement).click();
    const post = http.expectOne('/api/workspaces/ws-1/messages/m1/sends/0');
    expect(post.request.method).toBe('POST');
    post.flush({ messageId: 'm1', blockIndex: 0, kind: 'MAIL', subject: 'Compte forge', recipient: 'Zahi',
      sentAt: '2026-10-31T11:00:00Z', attenteKey: 'acces-forge' });
    fixture.detectChanges();
    expect(changed).toBe(1);
    expect(el.querySelector('.sit__item--pending')).toBeNull();
    const undo = el.querySelector('[data-send="MAIL|zahi|compte forge"] .sit__undo') as HTMLButtonElement;
    expect(undo).not.toBeNull();
    undo.click();
    const del = http.expectOne('/api/workspaces/ws-1/messages/m1/sends/0');
    expect(del.request.method).toBe('DELETE');
    del.flush(null);
    fixture.detectChanges();
    expect(changed).toBe(2);
    expect(el.querySelector('.sit__item--pending')?.textContent).toContain('pas marqué envoyé');
  });

  it('un geste refusé le dit et ne change rien', () => {
    const fixture = mount();
    const el: HTMLElement = fixture.nativeElement;
    (el.querySelector('.sit__mark') as HTMLButtonElement).click();
    http.expectOne('/api/workspaces/ws-1/messages/m1/sends/0').flush('non', { status: 500, statusText: 'KO' });
    fixture.detectChanges();
    expect(el.textContent).toContain('Impossible de marquer ce message comme envoyé.');
    expect(el.querySelector('.sit__item--pending')).not.toBeNull();
  });

  it('SF-225-03 : un même mail reproposé ne fait qu’une ligne (type, destinataire, objet), envoyé si l’un l’est', () => {
    const fixture = TestBed.createComponent(TerminalSituationComponent);
    fixture.componentRef.setInput('workspaceId', 'ws-1');
    fixture.detectChanges();
    const mail = SITUATION.envois[0];
    http.expectOne('/api/workspaces/ws-1/situation').flush({ ...SITUATION, envois: [
      { ...mail, messageId: 'm3', proposedAt: '2026-10-31T08:00:00Z' },
      { ...mail, messageId: 'm2', recipient: ' zahi ', subject: 'COMPTE FORGE' },
      mail,
      { ...mail, messageId: 'm4', recipient: 'Paul', subject: 'Accès' , sentAt: '2026-10-30T08:00:00Z' },
      { ...mail, messageId: 'm5', recipient: 'Paul', subject: 'Accès' },
    ] });
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    const pending = el.querySelectorAll('.sit__item--pending');
    expect(pending.length).toBe(1);
    expect(pending[0].textContent).toContain('Mail à Zahi');
    expect(pending[0].textContent).toContain('proposé 3 fois');
    const sent = el.querySelectorAll('.sit__item--sent');
    expect(sent.length).toBe(1);
    expect(sent[0].textContent).toContain('Mail à Paul : envoyé');
    (el.querySelector('.sit__mark') as HTMLButtonElement).click();
    // [Marquer envoyé] vise la proposition la plus récente du groupe.
    http.expectOne('/api/workspaces/ws-1/messages/m3/sends/0').flush({ messageId: 'm3', blockIndex: 0,
      kind: 'MAIL', subject: 'Compte forge', recipient: 'Zahi', sentAt: '2026-10-31T11:00:00Z', attenteKey: null });
  });

  it('SF-225-03 : le filtre par chantier resserre chantiers, mails et attentes ; le volet part sur son chantier', () => {
    const fixture = TestBed.createComponent(TerminalSituationComponent);
    fixture.componentRef.setInput('workspaceId', 'ws-1');
    fixture.componentRef.setInput('chantier', 2);
    fixture.detectChanges();
    http.expectOne('/api/workspaces/ws-1/situation').flush({ ...SITUATION,
      chantiers: [...SITUATION.chantiers, { ...SITUATION.chantiers[0], number: 3, title: 'Docs', focus: false }],
      envois: [
        { ...SITUATION.envois[0], chantierNumber: 2 },
        { ...SITUATION.envois[0], messageId: 'm9', recipient: 'Léa', subject: 'Docs', chantierNumber: 3 },
        { ...SITUATION.envois[1], chantierNumber: null },
      ],
      attentes: [...SITUATION.attentes,
        { id: 'a2', description: 'Relire la doc', status: 'A_FAIRE', qui: 'MOI', to: null, since: null, chantierNumber: null }],
    });
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    const chips = Array.from(el.querySelectorAll('.sit__chip')).map((c) => c.textContent?.trim());
    expect(chips).toEqual(['Tout', 'Ch.2 · Logs', 'Ch.3 · Docs', 'Au fil de l’eau']);
    expect(el.querySelector('[data-filter="c2"]')?.getAttribute('aria-pressed')).toBe('true');
    let text = el.textContent ?? '';
    expect(text).toContain('Chantier 2 — Logs');
    expect(text).not.toContain('Chantier 3 — Docs');
    expect(text).toContain('Mail à Zahi');
    expect(text).not.toContain('Mail à Léa');
    expect(text).not.toContain('Relire la doc');
    (el.querySelector('[data-filter="loose"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    text = el.textContent ?? '';
    expect(text).toContain('Message à l’équipe');
    expect(text).toContain('Relire la doc');
    expect(text).not.toContain('Mail à Zahi');
    (el.querySelector('[data-filter="all"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    text = el.textContent ?? '';
    expect(text).toContain('Mail à Léa');
    expect(text).toContain('Relire la doc');
  });

  it('en lecture seule : pas de [Marquer envoyé]', () => {
    const el: HTMLElement = mount(true).nativeElement;
    expect(el.querySelector('.sit__mark')).toBeNull();
    expect(el.querySelector('.sit__see')).not.toBeNull();
  });

  it('le seuil d\'ouverture se règle sur l\'appareil ; « jamais » coupe l\'ouverture automatique', () => {
    const fixture = mount();
    const select = fixture.nativeElement.querySelector('.sit__select') as HTMLSelectElement;
    expect(select.value).toBe('4');
    fixture.componentInstance.setThreshold('0');
    expect(localStorage.getItem('cg.situation.idleHours')).toBe('0');
    expect(situationOpensOnEntry({ idleHours: 6 }, 4)).toBeTrue();
    expect(situationOpensOnEntry({ idleHours: 3 }, 4)).toBeFalse();
    expect(situationOpensOnEntry({ idleHours: 99 }, 0)).toBeFalse();
    expect(situationOpensOnEntry({ idleHours: null }, 4)).toBeFalse();
    localStorage.removeItem('cg.situation.idleHours');
  });

  it('SF-225-03 : l’inactivité part du plus récent de votre dernier message et de votre dernière visite', () => {
    const now = Date.parse('2026-10-31T12:00:00Z');
    const base = { idleHours: 1, lastUserMessageAt: '2026-10-30T12:00:00Z', turnLive: false };
    expect(situationIdleHours(base, null, now)).toBe(24);
    expect(situationIdleHours(base, '2026-10-31T10:30:00Z', now)).toBe(1);
    expect(situationIdleHours({ idleHours: null, lastUserMessageAt: null }, null, now)).toBeNull();
    expect(situationIdleHours({ idleHours: 7 }, null, now)).toBe(7); // serveur d’avant SF-225-03
    expect(situationOpensOnEntry(base, 4, null, now)).toBeTrue();
    expect(situationOpensOnEntry(base, 4, '2026-10-31T10:30:00Z', now)).toBeFalse();
    expect(situationOpensOnEntry({ ...base, turnLive: true }, 4, null, now)).toBeFalse();
  });

  it('SF-225-03 : la dernière visite est notée par terminal, sur l’appareil', () => {
    localStorage.removeItem('cg.situation.visits');
    expect(readLastVisit('ws-9')).toBeNull();
    recordVisit('ws-9', new Date('2026-10-31T09:00:00Z'));
    expect(readLastVisit('ws-9')).toBe('2026-10-31T09:00:00.000Z');
    expect(readLastVisit('ws-8')).toBeNull();
    localStorage.setItem('cg.situation.visits', 'pas du json');
    expect(readLastVisit('ws-9')).toBeNull();
    localStorage.removeItem('cg.situation.visits');
  });

  it('/situation est une commande vue du registre', () => {
    const command = findPanelCommand('situation');
    expect(command?.kind).toBe('view');
    expect(command?.panelKind).toBe('situation');
  });
});
