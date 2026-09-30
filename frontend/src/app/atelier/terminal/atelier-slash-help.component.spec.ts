import { ComponentFixture, TestBed } from '@angular/core/testing';

import { AtelierSlashHelpComponent } from './atelier-slash-help.component';
import { SlashPanelHelpEntry } from './slash-panel-commands';

/**
 * Le CORPS du panneau /aide (F-165 / SF-165-01) : la liste des commandes. On vérifie qu'une ligne
 * est rendue par entrée fournie.
 */
describe('AtelierSlashHelpComponent (F-165 / SF-165-01)', () => {
  let fixture: ComponentFixture<AtelierSlashHelpComponent>;
  let component: AtelierSlashHelpComponent;

  const entries: SlashPanelHelpEntry[] = [
    { command: '/aide', kindLabel: 'Aide', description: 'Liste les commandes' },
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

  it('rend une ligne par entrée', () => {
    const items = (fixture.nativeElement as HTMLElement).querySelectorAll('.slash-help__item');
    expect(items.length).toBe(2);
    const names = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('.slash-help__name'))
      .map((n) => n.textContent?.trim());
    expect(names).toEqual(['/aide', '/cout']);
  });
});
