import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';

import { AtelierSlashSujetComponent } from './atelier-slash-sujet.component';
import { ThreadSujetSummary } from './slash-panel-commands';

/**
 * Le corps du panneau /sujet (F-165 / SF-165-05) : présentation pure. On vérifie les états et le rendu de
 * la carte (nom, client, moteur, tours, mode, plan).
 */
describe('AtelierSlashSujetComponent (F-165 / SF-165-05)', () => {
  let fixture: ComponentFixture<AtelierSlashSujetComponent>;
  let component: AtelierSlashSujetComponent;

  const sujet: ThreadSujetSummary = {
    turns: 12, mode: 'ANSWER_PLAN', planTotal: 3, hasFrontier: true,
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AtelierSlashSujetComponent, NoopAnimationsModule],
    }).compileComponents();
    fixture = TestBed.createComponent(AtelierSlashSujetComponent);
    component = fixture.componentInstance;
  });

  it('affiche le nom, le client et le moteur (issus de l\'écran) même en chargement', () => {
    component.state = 'loading';
    component.name = 'mon-projet';
    component.host = 'Poste ACME';
    component.engine = 'LOCAL_MACHINE';
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('mon-projet');
    expect(text).toContain('Poste ACME');
    expect(text).toContain('Poste (machine)');
    expect(fixture.nativeElement.querySelector('.sujet-note')).not.toBeNull();
  });

  it('rend tours, mode et plan en état « ready »', () => {
    component.state = 'ready';
    component.name = 'mon-projet';
    component.sujet = sujet;
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('12'); // tours
    expect(text).toContain('Plan'); // mode ANSWER_PLAN
    expect(text).toContain('3 étape(s)');
    expect(text).toContain('Posé'); // nouveau départ
    expect(component.modeLabel).toBe('Plan');
  });

  it('affiche « Agir » et « Aucun » plan par défaut', () => {
    component.state = 'ready';
    component.sujet = { turns: 0, mode: null, planTotal: 0, hasFrontier: false };
    fixture.detectChanges();
    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('Agir');
    expect(text).toContain('Aucun');
  });

  it('affiche l\'échec neutre', () => {
    component.state = 'error';
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.sujet-note--error')).not.toBeNull();
  });
});
