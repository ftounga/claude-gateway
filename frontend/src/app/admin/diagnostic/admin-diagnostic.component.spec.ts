import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';

import { AdminDiagnosticComponent, parityLabel, verdictLabel } from './admin-diagnostic.component';
import { AdminDiagnosticService } from './admin-diagnostic.service';
import { DiagnosticReport } from './admin-diagnostic.models';

function report(over: Partial<DiagnosticReport> = {}): DiagnosticReport {
  return {
    from: '2026-09-17T00:00:00Z', to: '2026-09-24T00:00:00Z', truncated: false,
    turns: 40, projects: 3, costEur: 100,
    findings: [{
      capabilityId: 'cache-de-prompt', name: 'Réutiliser le cache de prompt', verdict: 'DORMANTE',
      why: 'Aucun déclenchement sur la période.', where: ['backend/.../AnthropicAgentProvider.java'],
      check: 'Le préfixe reste stable d’un tour à l’autre.', gainEur: 25,
    }],
    discarded: 2, active: 6,
    parity: [
      { referenceId: 'cache', name: 'Réutiliser le cache de prompt', gives: 'ne pas repayer',
        state: 'DORMANTE', note: 'Portée mais jamais déclenchée.' },
      { referenceId: 'hooks', name: 'Hooks de cycle de vie', gives: 'déclencher des scripts',
        state: 'ECARTEE', note: 'écartée du périmètre par F-39' },
    ],
    specLines: ['| F-??? | Réutiliser le cache — … | **Candidate** — proposée par le diagnostic |'],
    ...over,
  };
}

/**
 * La section **Diagnostic du produit** (F-156 / SF-156-05).
 *
 * <p>Ce que ces tests tiennent : le <b>dénominateur</b> affiché, un constat avec son <b>endroit</b>,
 * la parité qui montre les hooks <b>écartés</b>, la ligne de spec <b>à copier</b> — et « rien à
 * signaler » rendu comme une conclusion.</p>
 */
describe('AdminDiagnosticComponent (F-156 / SF-156-05)', () => {
  let fixture: ComponentFixture<AdminDiagnosticComponent>;
  let component: AdminDiagnosticComponent;
  let service: jasmine.SpyObj<AdminDiagnosticService>;
  let snackBar: jasmine.SpyObj<MatSnackBar>;

  beforeEach(() => {
    service = jasmine.createSpyObj<AdminDiagnosticService>('AdminDiagnosticService', ['run']);
    snackBar = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);
    TestBed.configureTestingModule({
      imports: [AdminDiagnosticComponent, NoopAnimationsModule],
      providers: [provideHttpClient(), provideHttpClientTesting(),
        { provide: AdminDiagnosticService, useValue: service },
        { provide: MatSnackBar, useValue: snackBar }],
    });
    fixture = TestBed.createComponent(AdminDiagnosticComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  afterEach(() => TestBed.resetTestingModule());

  it("ne montre rien tant qu'on n'a pas lancé — le diagnostic est À LA DEMANDE", () => {
    expect(fixture.nativeElement.textContent).toContain('Lancer le diagnostic');
    expect(fixture.nativeElement.querySelector('.diag__denominator')).toBeNull();
    expect(service.run).not.toHaveBeenCalled();
  });

  it('rend le dénominateur, le constat avec son endroit, et les écartées', () => {
    service.run.and.returnValue(of(report()));

    component.run();
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent;
    expect(text).toContain('40 tours, 3 projets, 100.00 €');
    expect(text).toContain('Dormante');
    expect(text).toContain('Réutiliser le cache de prompt');
    expect(text).toContain('25.00 €');
    expect(text).toContain('À vérifier');
    expect(text).toContain('AnthropicAgentProvider.java');
    expect(text).toContain('2 autre(s) piste(s) écartée(s)');
  });

  it('la parité montre les hooks ÉCARTÉS avec leur raison — jamais comme un manque', () => {
    service.run.and.returnValue(of(report()));

    component.run();
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent;
    expect(text).toContain('Hooks de cycle de vie');
    expect(text).toContain('Écartée');
    expect(text).toContain('écartée du périmètre par F-39');
  });

  it('la ligne de spec est rendue À COPIER — rien n’est écrit dans la spec', () => {
    service.run.and.returnValue(of(report()));

    component.run();
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent;
    expect(text).toContain('À ajouter dans PRODUCT_SPEC.md');
    expect(text).toContain("L'application propose ; vous décidez");
    expect(fixture.nativeElement.querySelector('.spec-line').textContent)
      .toContain('**Candidate**');
  });

  it('« rien à signaler » est une CONCLUSION, avec le compte des capacités actives', () => {
    service.run.and.returnValue(of(report({ findings: [], specLines: [] })));

    component.run();
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent;
    expect(text).toContain('Rien à signaler');
    expect(text).toContain('6 capacités observées');
  });

  it('une période sans matière le dit, sans inventer de constat', () => {
    service.run.and.returnValue(of(report({ turns: 0, projects: 0, findings: [], specLines: [] })));

    component.run();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Rien à observer sur cette période');
  });

  it('un échec est dit, sans détail technique, et le bouton redevient actif', () => {
    service.run.and.returnValue(throwError(() => new Error('boom')));

    component.run();
    fixture.detectChanges();

    expect(snackBar.open).toHaveBeenCalled();
    expect(component.running()).toBeFalse();
    expect(fixture.nativeElement.textContent).not.toContain('boom');
  });

  describe('les libellés', () => {
    it('nomme les cinq états de parité', () => {
      expect(parityLabel('TENUE')).toBe('Tenue');
      expect(parityLabel('DORMANTE')).toBe('Dormante');
      expect(parityLabel('ABSENTE')).toBe('Absente');
      expect(parityLabel('ECARTEE')).toBe('Écartée');
      expect(parityLabel('NON_OBSERVEE')).toBe('Non observée');
    });

    it('nomme les trois verdicts', () => {
      expect(verdictLabel('ACTIVE')).toBe('Active');
      expect(verdictLabel('DORMANTE')).toBe('Dormante');
      expect(verdictLabel('INDETERMINEE')).toBe('Indéterminée');
    });
  });
});
