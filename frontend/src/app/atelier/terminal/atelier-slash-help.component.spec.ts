import { ComponentFixture, TestBed } from '@angular/core/testing';

import { AtelierSlashHelpComponent } from './atelier-slash-help.component';
import { SlashPanelHelpEntry } from './slash-panel-commands';

/**
 * Le CORPS du panneau /aide (F-165 / SF-165-01, groupé SF-165-06) : la liste des commandes, groupées par
 * famille (Vues / Actions / Aide). On vérifie le rendu et l'ordre des familles.
 */
describe('AtelierSlashHelpComponent (F-165 / SF-165-01)', () => {
  let fixture: ComponentFixture<AtelierSlashHelpComponent>;
  let component: AtelierSlashHelpComponent;

  const entries: SlashPanelHelpEntry[] = [
    { command: '/aide', kindLabel: 'Aide', description: 'Liste les commandes' },
    { command: '/rappel', kindLabel: 'Action', description: 'Retrouve des extraits' },
    { command: '/cout', kindLabel: 'Vue', description: 'L\'économie du fil' },
  ];

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AtelierSlashHelpComponent],
    }).compileComponents();
    fixture = TestBed.createComponent(AtelierSlashHelpComponent);
    component = fixture.componentInstance;
    component.entries = entries;
    fixture.detectChanges();
  });

  it('rend une ligne par entrée, groupée', () => {
    const items = (fixture.nativeElement as HTMLElement).querySelectorAll('.slash-help__item');
    expect(items.length).toBe(3);
  });

  it('groupe par famille dans l\'ordre Vue → Action → Aide', () => {
    const titles = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.slash-help__group-title'),
    ).map((n) => n.textContent?.trim());
    expect(titles).toEqual(['Vue', 'Action', 'Aide']);

    // Les commandes suivent l'ordre des familles, pas l'ordre d'entrée brut.
    const names = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.slash-help__name'),
    ).map((n) => n.textContent?.trim());
    expect(names).toEqual(['/cout', '/rappel', '/aide']);
  });
});
