import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { ProjectCostReport, ProjectCostService } from '../../core/services/project-cost.service';
import { ProjectCostComponent } from './project-cost.component';

/**
 * Ce que chaque projet a coûté (F-143 / SF-143-01).
 *
 * <p>Le fait qui compte : <b>deux montants</b>. Un projet calme cette semaine peut avoir coûté cher
 * depuis mars, et c'est le total qu'on relit pour décider — n'en montrer qu'un cacherait la moitié
 * de la décision.</p>
 */
describe('ProjectCostComponent', () => {
  @Component({
    imports: [ProjectCostComponent],
    template: `<app-project-cost [projectId]="projectId" [compact]="compact" />`,
  })
  class HostComponent {
    projectId: string | null = 'p1';
    compact = false;
  }

  let fixture: ComponentFixture<HostComponent>;
  let http: HttpTestingController;

  const URL = '/api/admin/cost/projects';

  const report: ProjectCostReport = {
    from: '2026-09-21',
    to: '2026-09-27',
    projects: [
      {
        id: 'p1',
        name: 'migration-bastion',
        hostId: 'h1',
        hostName: 'CAGIP',
        weekEur: 2,
        totalEur: 300,
        weekTurns: 3,
        totalTurns: 420,
      },
    ],
  };

  function setup(answer: ProjectCostReport | null, status = 200): void {
    TestBed.inject(ProjectCostService).load();
    const request = http.expectOne(URL);
    if (status === 200 && answer) {
      request.flush(answer);
    } else {
      request.flush('non', { status, statusText: 'Forbidden' });
    }
    fixture.detectChanges();
  }

  function dom(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HostComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    fixture = TestBed.createComponent(HostComponent);
    http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
  });

  afterEach(() => http.verify());

  it('LE CRITÈRE : la semaine ET le total', () => {
    setup(report);

    expect(dom().textContent).toContain('2,00 €');
    expect(dom().textContent).toContain('300,00 €');
    expect(dom().textContent).toContain('cette semaine · au total');
  });

  it('dit les tours dans l\'infobulle, pour distinguer « rien coûté » de « rien fait »', () => {
    setup(report);

    const label = dom().querySelector('.project-cost')?.getAttribute('title') ?? '';
    expect(label).toContain('3 tour(s)');
    expect(label).toContain('420 tour(s)');
  });

  it('n\'affiche rien pour un projet inconnu du rapport', () => {
    fixture.componentInstance.projectId = 'inconnu';
    setup(report);

    expect(dom().querySelector('.project-cost')).toBeNull();
  });

  it('n\'affiche rien quand la lecture est refusée, et ne casse pas l\'écran', () => {
    // 403 : le montant ne quitte pas le serveur pour qui n'est pas administrateur.
    setup(null, 403);

    expect(dom().querySelector('.project-cost')).toBeNull();
  });

  it('resserre l\'affichage pour la barre du terminal', () => {
    fixture.componentInstance.compact = true;
    setup(report);

    expect(dom().querySelector('.project-cost--compact')).not.toBeNull();
    expect(dom().textContent).not.toContain('cette semaine · au total');
  });

  it('ne lit qu\'UNE fois, même si les deux écrans le demandent', () => {
    const service = TestBed.inject(ProjectCostService);
    service.load();
    http.expectOne(URL).flush(report);

    service.load();
    http.expectNone(URL);
  });
});
