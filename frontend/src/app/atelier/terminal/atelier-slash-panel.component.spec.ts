import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';

import { AtelierSlashPanelComponent } from './atelier-slash-panel.component';
import { SlashPanel } from './slash-panel-commands';

/**
 * Le CADRE de panneau réutilisable (F-165 / SF-165-01). On vérifie l'en-tête (titre, jeton, badge de
 * famille) et l'émission de la fermeture.
 */
describe('AtelierSlashPanelComponent (F-165 / SF-165-01)', () => {
  let fixture: ComponentFixture<AtelierSlashPanelComponent>;
  let component: AtelierSlashPanelComponent;

  const panel: SlashPanel = {
    id: 'slash-0',
    command: '/aide',
    title: 'Commandes disponibles',
    icon: 'help_outline',
    kind: 'meta',
    panelKind: 'help',
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AtelierSlashPanelComponent, NoopAnimationsModule],
    }).compileComponents();
    fixture = TestBed.createComponent(AtelierSlashPanelComponent);
    component = fixture.componentInstance;
    component.panel = panel;
    fixture.detectChanges();
  });

  function dom(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  it('rend le titre, le jeton de commande et le badge de famille', () => {
    expect(dom().querySelector('.slash-panel__title')?.textContent).toContain('Commandes disponibles');
    expect(dom().querySelector('.slash-panel__command')?.textContent).toContain('/aide');
    expect(dom().querySelector('.slash-panel__badge')?.textContent).toContain('Aide');
  });

  it('émet « dismiss » au clic sur le bouton fermer', () => {
    const dismissed = jasmine.createSpy('dismiss');
    component.dismiss.subscribe(dismissed);
    (dom().querySelector('.slash-panel__close') as HTMLButtonElement).click();
    expect(dismissed).toHaveBeenCalledTimes(1);
  });
});
