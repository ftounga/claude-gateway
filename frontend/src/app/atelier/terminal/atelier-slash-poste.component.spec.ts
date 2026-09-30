import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';

import { AtelierSlashPosteComponent } from './atelier-slash-poste.component';
import { ThreadPosteSummary } from './slash-panel-commands';

/**
 * Le corps du panneau /poste (F-165 / SF-165-05) : présentation pure. On vérifie les états (chargement /
 * pas de poste / échec / prêt), l'état connecté et le rendu OS/shell + « vu il y a X ».
 */
describe('AtelierSlashPosteComponent (F-165 / SF-165-05)', () => {
  let fixture: ComponentFixture<AtelierSlashPosteComponent>;
  let component: AtelierSlashPosteComponent;

  const poste: ThreadPosteSummary = {
    name: 'Poste ACME', rootName: 'dev', os: 'Linux', shell: 'posix',
    elevated: false, connected: true, lastSeenAt: new Date().toISOString(),
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AtelierSlashPosteComponent, NoopAnimationsModule],
    }).compileComponents();
    fixture = TestBed.createComponent(AtelierSlashPosteComponent);
    component = fixture.componentInstance;
  });

  it('affiche le chargement sans planter', () => {
    fixture.componentRef.setInput('state', 'loading');
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.poste-note')).not.toBeNull();
  });

  it('affiche « pas de poste » (terminal hébergé)', () => {
    fixture.componentRef.setInput('state', 'none');
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('hébergé');
  });

  it('affiche un échec neutre', () => {
    fixture.componentRef.setInput('state', 'error');
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.poste-note--error')).not.toBeNull();
  });

  it('rend l\'état connecté, l\'OS, le shell et « vu il y a X » en état « ready »', () => {
    component.state = 'ready';
    component.poste = poste;
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('Poste ACME');
    expect(text).toContain('Connecté');
    expect(text).toContain('Linux');
    expect(text).toContain('POSIX');
    expect(text).toContain('vu il y a');
    expect(fixture.nativeElement.querySelector('.poste-dot.is-on')).not.toBeNull();
  });

  it('rend « Hors ligne » quand le poste n\'est pas connecté', () => {
    component.state = 'ready';
    component.poste = { ...poste, connected: false, lastSeenAt: null };
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Hors ligne');
    expect(fixture.nativeElement.querySelector('.poste-dot.is-on')).toBeNull();
    expect(component.seenAgo).toBe('');
  });
});
