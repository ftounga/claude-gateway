import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { of } from 'rxjs';

import { TerminalVeillesBandComponent } from './terminal-veilles-band.component';
import { JarvisWatch } from '../../core/models/veille.models';
import { VeilleService } from '../../core/services/veille.service';

export function watch(over: Partial<JarvisWatch> = {}): JarvisWatch {
  return {
    id: 'v-1', workspaceId: 'w-1', chantierNumber: 2, name: 'Agenor', platform: 'Jira',
    scope: 'En cours, bloqués ; jamais les done', command: 'bash .atelier/veilles/agenor.sh',
    scriptPath: '.atelier/veilles/agenor.sh', runTimes: ['09:00', '14:00'], timeZone: 'Europe/Paris',
    days: 'MONDAY_TO_FRIDAY', active: false, proposed: true, origin: 'AGENT', testedAt: null, testItemCount: null,
    testSummary: null, nextRunAt: null, createdAt: '', updatedAt: '', ...over,
  };
}

/** **La carte de validation d'une veille** (F-230 / SF-230-01) : la commande en clair, tester, puis activer. */
describe('La bande des veilles Jarvis (F-230 / SF-230-01)', () => {
  let fixture: ComponentFixture<TerminalVeillesBandComponent>;
  let service: jasmine.SpyObj<VeilleService>;

  function create(watches: JarvisWatch[]): void {
    service.list.and.returnValue(of(watches));
    fixture = TestBed.createComponent(TerminalVeillesBandComponent);
    fixture.componentRef.setInput('workspaceId', 'w-1');
    fixture.detectChanges();
  }

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  function button(label: string): HTMLButtonElement {
    const all = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button'));
    return all.find((b) => (b.textContent ?? '').includes(label)) as HTMLButtonElement;
  }

  beforeEach(() => {
    service = jasmine.createSpyObj<VeilleService>('VeilleService', ['list', 'test', 'accept', 'remove']);
    TestBed.configureTestingModule({
      imports: [TerminalVeillesBandComponent, NoopAnimationsModule],
      providers: [{ provide: VeilleService, useValue: service }],
    });
  });

  it('montre la commande en clair et les heures, lundi → vendredi', () => {
    create([watch(), watch({ id: 'v-2', name: 'Active', proposed: false, active: true })]);
    expect(text()).toContain('Veille proposée · Agenor');
    expect(text()).toContain('Lundi → vendredi · 09:00, 14:00');
    expect(text()).toContain('bash .atelier/veilles/agenor.sh');
    expect(text()).not.toContain('Veille proposée · Active');
  });

  it('absente quand rien n’est proposé', () => {
    create([watch({ proposed: false, active: true })]);
    expect((fixture.nativeElement as HTMLElement).querySelector('.veille-card')).toBeNull();
  });

  it('[Activer] reste désactivé avant un test réussi, puis s’ouvre', () => {
    create([watch()]);
    expect(button('Activer').disabled).toBeTrue();
    service.test.and.returnValue(of({
      ok: true, itemCount: 12, summary: '12 éléments : 5 en cours, 2 bloqués', sample: ['AG-1 Alarme'],
      errorMessage: null,
    }));
    button('Tester la sonde').click();
    fixture.detectChanges();
    expect(service.test).toHaveBeenCalledWith('w-1', 'v-1');
    expect(text()).toContain('Je vois 12 éléments : 5 en cours, 2 bloqués');
    expect(text()).toContain('AG-1 Alarme');
    expect(button('Activer').disabled).toBeFalse();
    service.accept.and.returnValue(of(watch({ proposed: false, active: true })));
    button('Activer').click();
    expect(service.accept).toHaveBeenCalledWith('w-1', 'v-1');
  });

  it('un test en échec dit pourquoi et garde [Activer] fermé', () => {
    create([watch()]);
    service.test.and.returnValue(of({
      ok: false, itemCount: null, summary: null, sample: [], errorMessage: 'jeton expiré',
    }));
    button('Tester la sonde').click();
    fixture.detectChanges();
    expect(text()).toContain('La sonde a échoué : jeton expiré');
    expect(button('Activer').disabled).toBeTrue();
  });

  it('[Refuser] supprime la proposition', () => {
    create([watch()]);
    service.remove.and.returnValue(of(void 0));
    button('Refuser').click();
    expect(service.remove).toHaveBeenCalledWith('w-1', 'v-1');
  });

  it('en lecture seule, aucun geste', () => {
    service.list.and.returnValue(of([watch()]));
    fixture = TestBed.createComponent(TerminalVeillesBandComponent);
    fixture.componentRef.setInput('workspaceId', 'w-1');
    fixture.componentRef.setInput('readOnly', true);
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelectorAll('button').length).toBe(0);
    expect(text()).toContain('En attente de validation.');
  });
});
