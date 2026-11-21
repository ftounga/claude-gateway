import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';

import { QuickAction } from '../../core/services/quick-actions.service';
import { TerminalQuickActionsComponent, quickActionMeta } from './terminal-quick-actions.component';

function action(over: Partial<QuickAction> = {}): QuickAction {
  return {
    key: 'c:1', source: 'PERSO', id: '1', label: 'Point hebdo', phrase: 'point hebdo', skill: null, scope: 'POSTE',
    targetWorkspaceId: null, targetName: null, targetMissing: false, clickMode: 'DEPOSER', suggestDays: [],
    suggestMoment: null, lastRunAt: null, ...over,
  };
}

/** Chaque jour, toute la journée : suggérée quel que soit le jour où le test tourne. */
const EVERY_DAY = [1, 2, 3, 4, 5, 6, 7];

describe('F-275 — le bouton « Actions rapides »', () => {
  let fixture: ComponentFixture<TerminalQuickActionsComponent>;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [TerminalQuickActionsComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideNoopAnimations()],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(TerminalQuickActionsComponent);
    fixture.componentRef.setInput('workspaceId', 'w1');
    fixture.detectChanges();
  });

  afterEach(() => http.verify());

  function openMenu(full: QuickAction[]): void {
    (fixture.nativeElement.querySelector('.quick-actions__trigger') as HTMLButtonElement).click();
    fixture.detectChanges();
    http.expectOne('/api/workspaces/w1/quick-actions?skills=true').flush(full);
    fixture.detectChanges();
  }

  function menuText(): string {
    return document.querySelector('.quick-actions-menu')?.textContent ?? '';
  }

  it('lit seulement les actions perso au chargement et allume le point d\'une suggestion', () => {
    http.expectOne('/api/workspaces/w1/quick-actions?skills=false')
      .flush([action({ label: 'Veille du comité', suggestDays: EVERY_DAY, suggestMoment: 'JOURNEE' })]);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.quick-actions__dot')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('.quick-actions__trigger').getAttribute('aria-label'))
      .toContain('1 suggérée');
  });

  it('relit la liste complète à l\'ouverture et la range en sections', () => {
    http.expectOne('/api/workspaces/w1/quick-actions?skills=false').flush([]);
    openMenu([
      action({ key: 'c:2', label: 'Veille du comité', suggestDays: EVERY_DAY, suggestMoment: 'JOURNEE' }),
      action({ key: 'c:1', label: 'Point hebdo', targetName: 'reporting-securite', clickMode: 'ENVOYER' }),
      action({ key: 's:h:suivi-hebdo:suivi du jour', source: 'SKILL', id: null, label: 'Suivi du jour',
        phrase: 'suivi du jour', skill: 'suivi-hebdo' }),
    ]);

    const text = menuText();
    expect(text).toContain('Suggéré maintenant');
    expect(text).toContain('Mes actions');
    expect(text).toContain('Depuis vos skills');
    expect(text).toContain('/suivi-hebdo');
    expect(text).toContain('→ reporting-securite · envoi direct');
  });

  it('émet l\'action cliquée', () => {
    http.expectOne('/api/workspaces/w1/quick-actions?skills=false').flush([]);
    const launched: QuickAction[] = [];
    fixture.componentInstance.launch.subscribe((a) => launched.push(a));
    openMenu([action()]);

    (document.querySelector('.quick-actions__item') as HTMLButtonElement).click();

    expect(launched.map((a) => a.key)).toEqual(['c:1']);
  });

  it('dit comment créer une action quand il n\'y en a aucune, et l\'échec de lecture', () => {
    http.expectOne('/api/workspaces/w1/quick-actions?skills=false').flush([]);
    openMenu([]);
    expect(menuText()).toContain('Aucune action rapide ici');
    expect(menuText()).toContain('ajoute une action rapide');
  });

  it('relit la liste légère quand le terminal signale une fin de tour', () => {
    http.expectOne('/api/workspaces/w1/quick-actions?skills=false').flush([]);
    fixture.componentRef.setInput('refresh', 1);
    fixture.detectChanges();
    http.expectOne('/api/workspaces/w1/quick-actions?skills=false').flush([]);
  });

  it('écrit la ligne d\'aide : où, comment, quand', () => {
    http.expectOne('/api/workspaces/w1/quick-actions?skills=false').flush([]);
    expect(quickActionMeta(action(), new Date())).toBe('ici · dans la saisie · jamais lancée');
    expect(quickActionMeta(action({ targetMissing: true, targetWorkspaceId: 'x' }), new Date()))
      .toContain('sujet introuvable : ici');
  });
});
