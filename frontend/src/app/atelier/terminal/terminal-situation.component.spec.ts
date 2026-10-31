import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';

import { TerminalSituationComponent } from './terminal-situation.component';
import { Situation, situationOpensOnEntry } from '../../core/models/situation.models';
import { findPanelCommand } from './slash-panel-commands';

const SITUATION: Situation = {
  generatedAt: '2026-10-31T10:00:00Z',
  lastActivityAt: '2026-10-31T04:00:00Z',
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
describe('F-225 / SF-225-02 — le point de situation', () => {
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

  it('[Voir] montre le texte, [Marquer envoyé] émet le geste et met la carte à jour', () => {
    const fixture = mount();
    const el: HTMLElement = fixture.nativeElement;
    const emitted: { messageId: string; index: number }[] = [];
    fixture.componentInstance.markSent.subscribe((e) => emitted.push(e));
    (el.querySelector('.sit__see') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(el.querySelector('.sit__excerpt')?.textContent).toContain('Objet : Compte forge — Bonjour Zahi');
    (el.querySelector('.sit__mark') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(emitted).toEqual([{ messageId: 'm1', index: 0 }]);
    expect(el.querySelector('.sit__item--pending')).toBeNull();
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

  it('/situation est une commande vue du registre', () => {
    const command = findPanelCommand('situation');
    expect(command?.kind).toBe('view');
    expect(command?.panelKind).toBe('situation');
  });
});
