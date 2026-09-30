import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';

import { AtelierSlashQuotaComponent } from './atelier-slash-quota.component';
import { ThreadQuotaSummary } from './slash-panel-commands';

/**
 * Le corps du panneau /quota (F-165 / SF-165-04) : présentation pure. On vérifie les trois états et le
 * rendu de la jauge, du restant et de la période.
 */
describe('AtelierSlashQuotaComponent (F-165 / SF-165-04)', () => {
  let fixture: ComponentFixture<AtelierSlashQuotaComponent>;
  let component: AtelierSlashQuotaComponent;

  const quota: ThreadQuotaSummary = {
    usedTokens: 800000, quotaTokens: 1000000, remainingTokens: 200000,
    processedTokens: 5000000, periodStart: '2026-09-01', periodEnd: '2026-10-01',
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AtelierSlashQuotaComponent, NoopAnimationsModule],
    }).compileComponents();
    fixture = TestBed.createComponent(AtelierSlashQuotaComponent);
    component = fixture.componentInstance;
  });

  it('affiche un état de chargement sans planter', () => {
    component.state = 'loading';
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.quota-note')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('.quota-figures')).toBeNull();
  });

  it('affiche un état d\'échec neutre', () => {
    component.state = 'error';
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.quota-note--error')).not.toBeNull();
  });

  it('rend la jauge, le restant et la période en état « ready »', () => {
    component.state = 'ready';
    component.quota = quota;
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('80 %'); // used / quota = 800k / 1M
    expect(component.usedPercent).toBe(80);
    expect(component.nearLimit).toBe(true);
    expect(fixture.nativeElement.querySelector('.quota-bar__seg')).not.toBeNull();
    // Le restant est affiché (200 000 → « 200 000 » avec séparateur fr).
    expect(text.replace(/ | |\s/g, '')).toContain('200000');
  });

  it('borne la jauge et ne signale pas d\'alerte sous 80 %', () => {
    component.state = 'ready';
    component.quota = { ...quota, usedTokens: 100000 };
    fixture.detectChanges();
    expect(component.usedPercent).toBe(10);
    expect(component.nearLimit).toBe(false);
  });
});
