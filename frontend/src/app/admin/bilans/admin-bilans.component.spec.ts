import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';

import { AdminBilansComponent, axisLabel, durationLabel } from './admin-bilans.component';
import { AdminBilansService } from './admin-bilans.service';
import { BilanDetail, BilanSummary } from './admin-bilans.models';

function summary(over: Partial<BilanSummary> = {}): BilanSummary {
  return {
    id: 'b-1', workspaceId: 'w-1', workspaceName: 'AGENOR',
    fromAt: '2026-09-24T08:00:00Z', toAt: '2026-09-24T10:00:00Z', origin: 'AUTOMATIQUE',
    turns: 12, costEur: 9.4, cacheShare: 17, suggestionCount: 1, discardedCount: 2,
    createdAt: '2026-09-24T10:00:00Z', ...over,
  };
}

function detail(over: Partial<BilanDetail> = {}): BilanDetail {
  return {
    headline: summary(),
    ledger: {
      turns: 12, elapsed: 'PT2H', toolCalls: 40, failedTools: 3, filesWritten: 5,
      costEur: 9.4, inputTokens: 500000, outputTokens: 20000, cacheReadTokens: 100000,
      cacheWriteTokens: 1000, cacheShare: 17, turnsWithoutCost: 1, model: 'claude-opus-5',
      costliestTurns: [{
        occurredAt: '2026-09-24T09:00:00Z', model: 'claude-opus-5', costEur: 4.1,
        inputTokens: 200000, outputTokens: 3000, cacheReadTokens: 0,
      }],
      heaviestTools: [{ tool: 'bash', calls: 6, total: 'PT3M20S', failures: 3 }],
    },
    suggestions: [{
      axis: 'COUT', advice: 'Gardez le début stable.', measure: 'cache lu : 17 % sur 12 tours',
      gainPct: 28, gainEur: 2.6,
    }],
    ...over,
  };
}

/**
 * La section **Bilans de session** de l'administration (F-155 / SF-155-04).
 *
 * <p>Ce que ces tests tiennent : les cinq chiffres qui permettent de **comparer**, la suggestion
 * avec **sa mesure**, les **écartées** dites plutôt que tues, et « **rien à signaler** » rendu
 * comme une conclusion — pas comme un vide.</p>
 */
describe('AdminBilansComponent (F-155 / SF-155-04)', () => {
  let fixture: ComponentFixture<AdminBilansComponent>;
  let component: AdminBilansComponent;
  let service: jasmine.SpyObj<AdminBilansService>;
  let snackBar: jasmine.SpyObj<MatSnackBar>;

  function build(list: BilanSummary[]): void {
    service = jasmine.createSpyObj<AdminBilansService>('AdminBilansService',
      ['list', 'open', 'produce']);
    service.list.and.returnValue(of(list));
    snackBar = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);
    TestBed.configureTestingModule({
      imports: [AdminBilansComponent, NoopAnimationsModule],
      providers: [provideHttpClient(), provideHttpClientTesting(),
        { provide: AdminBilansService, useValue: service },
        { provide: MatSnackBar, useValue: snackBar }],
    });
    fixture = TestBed.createComponent(AdminBilansComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  afterEach(() => TestBed.resetTestingModule());

  it('rend les cinq chiffres qui permettent de comparer deux semaines', () => {
    build([summary()]);

    const text = fixture.nativeElement.textContent;
    expect(text).toContain('AGENOR');
    expect(text).toContain('12');
    // Le projet n'enregistre aucune locale : les nombres sortent au format par défaut, ici comme
    // partout ailleurs dans l'application. On s'aligne plutôt que d'introduire une exception.
    expect(text).toContain('9.40 €');
    expect(text).toContain('17 %');
    expect(text).toContain('(+2)'); // les écartées, dites
  });

  it('dit ce qu’il faut faire quand il n’y a aucun bilan, sans laisser un vide', () => {
    build([]);
    expect(fixture.nativeElement.textContent).toContain('Aucun bilan');
    expect(fixture.nativeElement.textContent).toContain('seuil');
  });

  it('ouvre un bilan : les trois parties, et la suggestion avec SA MESURE', () => {
    build([summary()]);
    service.open.and.returnValue(of(detail()));

    component.open(summary());
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent;
    expect(text).toContain('Ce qui a été fait');
    expect(text).toContain('Ce que ça a coûté');
    expect(text).toContain("Ce qui aurait mieux valu");
    expect(text).toContain('12 tours en 2 h');
    expect(text).toContain('1 tour(s) sans coût connu'); // jamais noyé dans le total
    expect(text).toContain('Coût · +28 %');
    expect(text).toContain('mesuré : cache lu : 17 % sur 12 tours');
    expect(text).toContain('« bash »');
  });

  it('« rien à signaler » est rendu comme une CONCLUSION, avec les écartées', () => {
    build([summary({ suggestionCount: 0 })]);
    service.open.and.returnValue(of(detail({ suggestions: [] })));

    component.open(summary());
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent;
    expect(text).toContain('Rien à signaler');
    expect(text).toContain('bien menée');
    expect(text).toContain('2 piste(s) écartée(s)');
  });

  it('un échec d’ouverture est dit, sans détail technique', () => {
    build([summary()]);
    service.open.and.returnValue(throwError(() => new Error('boom')));

    component.open(summary());
    fixture.detectChanges();

    expect(snackBar.open).toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).not.toContain('boom');
  });

  it('un chargement en échec le dit, sans page blanche', () => {
    service = jasmine.createSpyObj<AdminBilansService>('AdminBilansService',
      ['list', 'open', 'produce']);
    service.list.and.returnValue(throwError(() => new Error('réseau')));
    TestBed.configureTestingModule({
      imports: [AdminBilansComponent, NoopAnimationsModule],
      providers: [provideHttpClient(), provideHttpClientTesting(),
        { provide: AdminBilansService, useValue: service }],
    });
    fixture = TestBed.createComponent(AdminBilansComponent);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain("n'ont pas pu être chargés");
  });

  describe('les durées et les axes, lisibles', () => {
    it('rend une durée ISO en français', () => {
      expect(durationLabel('PT2H')).toBe('2 h');
      expect(durationLabel('PT3M20S')).toBe('3 min');
      expect(durationLabel('PT45S')).toBe('45 s');
      expect(durationLabel(null)).toBe('—');
      expect(durationLabel('pas une durée')).toBe('—');
    });

    it('nomme les trois axes du PO, et rend les autres tels quels', () => {
      expect(axisLabel('COUT')).toBe('Coût');
      expect(axisLabel('TEMPS')).toBe('Temps');
      expect(axisLabel('RAISONNEMENT')).toBe('Raisonnement');
      expect(axisLabel('AUTRE')).toBe('AUTRE');
    });
  });
});
