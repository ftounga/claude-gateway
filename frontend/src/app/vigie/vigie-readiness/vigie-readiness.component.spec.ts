import { SimpleChange } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';

import { VigieReadinessComponent } from './vigie-readiness.component';
import {
  VigieCheckStatus,
  VigieReadiness,
  VigieReadinessCheck,
} from '../../core/models/vigie-readiness.models';

/** F-122 / SF-122-02 — l'assistant affiche les états et ne débloque « démarrer » que tout au vert. */
describe('VigieReadinessComponent', () => {
  let fixture: ComponentFixture<VigieReadinessComponent>;
  let component: VigieReadinessComponent;
  let httpMock: HttpTestingController;

  const CHECKS: VigieReadinessCheck[] = [
    'RUNNER_CONNECTED',
    'CHROME_REACHABLE',
    'TEAMS_CONNECTED',
    'TEAMS_READ_TEST',
  ];

  function readiness(statuses: VigieCheckStatus[], extra: Partial<VigieReadiness> = {}): VigieReadiness {
    return {
      checks: CHECKS.map((check, i) => ({ check, status: statuses[i], detail: check })),
      canStart: statuses.every((s) => s === 'OK'),
      teamsSignInRequired: false,
      ...extra,
    };
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [VigieReadinessComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideNoopAnimations()],
    }).compileComponents();
    fixture = TestBed.createComponent(VigieReadinessComponent);
    component = fixture.componentInstance;
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  function load(value: VigieReadiness): void {
    component.hostId = 'h1';
    component.ngOnChanges({ hostId: new SimpleChange(undefined, 'h1', true) });
    const req = httpMock.expectOne('/api/runner-hosts/h1/vigie/readiness');
    expect(req.request.method).toBe('GET');
    req.flush(value);
    fixture.detectChanges();
  }

  it('affiche les quatre vérifications', () => {
    load(readiness(['OK', 'PENDING', 'PENDING', 'PENDING']));

    const rows = fixture.nativeElement.querySelectorAll('.readiness__row');
    expect(rows.length).toBe(4);
  });

  it('garde « Démarrer » désactivé tant que tout n\'est pas vert, et propose le login Teams', () => {
    load(readiness(['OK', 'OK', 'KO', 'KO'], { teamsSignInRequired: true }));

    const start: HTMLButtonElement = fixture.nativeElement.querySelector('.readiness__start');
    expect(start.disabled).toBe(true);

    const labels = Array.from(fixture.nativeElement.querySelectorAll('button')).map(
      (b) => (b as HTMLElement).textContent ?? '',
    );
    expect(labels.some((t) => t.includes('Se connecter à Teams'))).toBe(true);
  });

  it('débloque « Démarrer » et émet start quand tout est vert', () => {
    load(readiness(['OK', 'OK', 'OK', 'OK']));

    const start: HTMLButtonElement = fixture.nativeElement.querySelector('.readiness__start');
    expect(start.disabled).toBe(false);

    let emitted: string | undefined;
    component.start.subscribe((value) => (emitted = value));
    start.click();
    expect(emitted).toBe('h1');
  });
});
