import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';

import { AtelierSlashContexteComponent } from './atelier-slash-contexte.component';
import { ThreadContextSummary } from './slash-panel-commands';

/**
 * Le corps du panneau /contexte (F-165 / SF-165-03) : présentation pure. On vérifie les trois états
 * (chargement / échec / prêt), le rendu de la jauge et des compteurs, et la présence/absence du résumé
 * ancré.
 */
describe('AtelierSlashContexteComponent (F-165 / SF-165-03)', () => {
  let fixture: ComponentFixture<AtelierSlashContexteComponent>;
  let component: AtelierSlashContexteComponent;

  const summary: ThreadContextSummary = {
    contextTokens: 60000, contextPages: 120, liveTurns: 3, foldedTurns: 5,
    hasAnchoredSummary: true, compactionEnabled: true, triggerTokens: 120000,
    triggerPages: 240, fillPercent: 50, keepRecentTurns: 6, recallSemantic: true,
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AtelierSlashContexteComponent, NoopAnimationsModule],
    }).compileComponents();
    fixture = TestBed.createComponent(AtelierSlashContexteComponent);
    component = fixture.componentInstance;
  });

  it('affiche un état de chargement sans planter', () => {
    component.state = 'loading';
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.ctx-note')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('.ctx-figures')).toBeNull();
  });

  it('affiche un état d\'échec neutre', () => {
    component.state = 'error';
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.ctx-note--error')).not.toBeNull();
  });

  it('rend la jauge et les compteurs en état « ready »', () => {
    component.state = 'ready';
    component.context = summary;
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('120'); // pages vivantes
    expect(text).toContain('50 %'); // progression vers le seuil
    expect(text).toContain('Présent'); // résumé ancré
    expect(text).toContain('sémantique'); // rappel
    expect(fixture.nativeElement.querySelector('.ctx-bar__seg')).not.toBeNull();
    expect(component.fillWidth).toBe(50);
    expect(component.nearThreshold).toBe(false);
  });

  it('signale l\'approche du seuil (jauge en alerte ≥ 80 %) et borne la largeur à 100 %', () => {
    component.state = 'ready';
    component.context = { ...summary, fillPercent: 130 };
    fixture.detectChanges();
    expect(component.fillWidth).toBe(100);
    expect(component.nearThreshold).toBe(true);
  });

  it('affiche « Aucun » quand il n\'y a pas de résumé ancré', () => {
    component.state = 'ready';
    component.context = { ...summary, hasAnchoredSummary: false, recallSemantic: false };
    fixture.detectChanges();
    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('Aucun');
    expect(text).toContain('Mot-clé seul');
  });
});
