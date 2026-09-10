import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';

import { WorkstationNoticeService } from '../../core/services/workstation-notice.service';
import { WorkstationNoticeComponent } from './workstation-notice.component';

const STORAGE_KEY = 'cg_workstation_notice';
const HOUR = 60 * 60 * 1000;

/**
 * F-57 / SF-57-03 — le bandeau de rappel : ce qu'il dit, et ce qu'il ne fait pas.
 *
 * <p>Le ton est un critère d'acceptation à part entière : « vraisemblablement », jamais « vous êtes
 * surveillé ». Le produit ne sait pas, et il ne doit pas prétendre savoir.</p>
 */
describe('WorkstationNoticeComponent', () => {
  let fixture: ComponentFixture<WorkstationNoticeComponent>;

  function build(): ComponentFixture<WorkstationNoticeComponent> {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [WorkstationNoticeComponent, NoopAnimationsModule],
    });
    const created = TestBed.createComponent(WorkstationNoticeComponent);
    created.detectChanges();
    return created;
  }

  beforeEach(() => {
    localStorage.removeItem(STORAGE_KEY);
  });

  afterEach(() => {
    fixture?.destroy();
    localStorage.removeItem(STORAGE_KEY);
  });

  it('s’affiche quand le rappel est dû', () => {
    fixture = build();

    const notice = fixture.nativeElement.querySelector('.notice');
    expect(notice).withContext('le bandeau doit être présent').not.toBeNull();
  });

  it('ne s’affiche pas quand le dernier rappel est récent', () => {
    localStorage.setItem(
      STORAGE_KEY,
      JSON.stringify({ version: 1, intervalHours: 2, acknowledgedAt: Date.now() - HOUR }),
    );

    fixture = build();

    expect(fixture.nativeElement.querySelector('.notice')).toBeNull();
  });

  it('dit « vraisemblablement », et jamais que le poste EST surveillé', () => {
    fixture = build();
    const text = (fixture.nativeElement.textContent ?? '') as string;

    expect(text).toContain('vraisemblablement journalisées');
    expect(text).toContain('ne cherche pas à le savoir');
    expect(text).not.toContain('vous êtes surveillé');
  });

  it('n’est pas modal : aucun masque, aucun piège à focus', () => {
    fixture = build();
    const host = fixture.nativeElement as HTMLElement;

    expect(host.querySelector('.cdk-overlay-backdrop')).toBeNull();
    expect(host.querySelector('[cdkTrapFocus]')).toBeNull();
    // `status` et non `alert` : un rappel de responsabilité n'interrompt pas un lecteur d'écran.
    expect(host.querySelector('.notice')?.getAttribute('role')).toBe('status');
  });

  it('« Compris » referme le bandeau et repart le compteur', () => {
    fixture = build();
    const service = TestBed.inject(WorkstationNoticeService);

    const acknowledge = fixture.nativeElement.querySelector('.notice-ack') as HTMLButtonElement;
    acknowledge.click();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.notice')).toBeNull();
    expect(service.isDue(Date.now())).toBeFalse();
  });

  it('affiche la périodicité courante et la transmet au service quand elle change', () => {
    fixture = build();
    const service = TestBed.inject(WorkstationNoticeService);

    const trigger = fixture.nativeElement.querySelector('.notice-interval') as HTMLElement;
    expect(trigger.textContent).toContain('2 h');

    fixture.componentInstance.chooseInterval(24);
    fixture.detectChanges();

    expect(service.intervalHours()).toBe(24);
  });

  it('« jamais » éteint le rappel', () => {
    fixture = build();
    const service = TestBed.inject(WorkstationNoticeService);

    fixture.componentInstance.chooseInterval(null);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.notice')).toBeNull();
    expect(service.isDue(Date.now() + 100 * HOUR)).toBeFalse();
  });
});
