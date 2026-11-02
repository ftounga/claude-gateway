import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';

import { JarvisWatchSummary } from '../../../core/models/veille.models';
import { VeilleService } from '../../../core/services/veille.service';
import type { Ambient } from '../../scenes/jarvis-scene-bus.service';
import { NIGHT_AWAKE_MS, NightWake, nextWatches, nightAlerts } from './jarvis-night';
import { JarvisNightComponent } from './jarvis-night.component';

const QUIET: Ambient = { hostDown: null, liveSubjects: new Set(), calling: null, cost: '0,42 €', alert: null };

function watch(id: string, nextRunAt: string | null, extra: Record<string, unknown> = {}): JarvisWatchSummary {
  return { workspaceName: 'Migration Forge', watch: { id, name: `Veille ${id}`, active: true, proposed: false,
    nextRunAt, ...extra } } as unknown as JarvisWatchSummary;
}

describe('F-236 / SF-236-24 — veilleur de nuit (pur)', () => {
  it('alertes = ambiance réelle (veille, poste muet, appel) ; jamais le coût', () => {
    expect(nightAlerts(QUIET)).toEqual([]);
    expect(nightAlerts(null)).toEqual([]);
    const all = nightAlerts({ ...QUIET, alert: { clientKey: 'h-cagip', label: 'FORGE-41 a changé' },
      hostDown: { clientKey: 'h-eden', name: 'Edenred' }, calling: { subjectId: 's-doc', label: 'Doc archi appelle' } });
    expect(all.map((a) => a.text)).toEqual(['FORGE-41 a changé', 'Poste muet : Edenred', 'Doc archi appelle']);
    expect(JSON.stringify(all)).not.toContain('0,42');
  });

  it('prochaines veilles : actives, à venir, triées, au plus 3 ; jour écrit si ce n’est pas aujourd’hui', () => {
    const now = new Date(2026, 10, 2, 22, 0); // lundi 2 novembre, 22 h
    const at = (d: number, h: number, m = 0) => new Date(2026, 10, d, h, m).toISOString();
    const list = [watch('a', at(3, 7, 30)), watch('b', at(2, 23, 15)), watch('c', null), watch('d', at(1, 7)),
      watch('e', at(4, 8), { active: false }), watch('f', at(3, 9), { proposed: true }), watch('g', at(5, 8)),
      watch('h', at(6, 8))];
    const next = nextWatches(list, now);
    expect(next.map((w) => w.id)).toEqual(['b', 'a', 'g']);
    expect(next[0].when).toBe('23:15');
    expect(next[1].when).toBe('mar. 07:30');
    expect(nextWatches(null, now)).toEqual([]);
  });

  it('réveil : une alerte NOUVELLE réveille une minute ; la même alerte ne réveille pas deux fois ; un toucher aussi', () => {
    const wake = new NightWake();
    expect(wake.observe([], 0)).toBeFalse();
    expect(wake.awake(0)).toBeFalse();
    expect(wake.observe([{ key: 'host:h-eden', text: 'x' }], 1000)).toBeTrue();
    expect(wake.awake(1000 + NIGHT_AWAKE_MS - 1)).toBeTrue();
    expect(wake.awake(1000 + NIGHT_AWAKE_MS)).toBeFalse();
    expect(wake.observe([{ key: 'host:h-eden', text: 'x' }], 90_000)).toBeFalse();
    wake.touch(200_000);
    expect(wake.awake(200_000 + 10)).toBeTrue();
  });
});

describe('F-236 / SF-236-24 — JarvisNightComponent', () => {
  it('horloge, veilles lues une fois, réveil à l’alerte, sortie', () => {
    const veilles = jasmine.createSpyObj<VeilleService>('VeilleService', ['listForAccount']);
    veilles.listForAccount.and.returnValue(of([watch('a', new Date(Date.now() + 3_600_000).toISOString())]));
    TestBed.configureTestingModule({ imports: [JarvisNightComponent],
      providers: [{ provide: VeilleService, useValue: veilles }] });
    const fixture = TestBed.createComponent(JarvisNightComponent);
    fixture.componentRef.setInput('ambient', QUIET);
    fixture.detectChanges();
    const night = fixture.componentInstance;
    const el: HTMLElement = fixture.nativeElement;
    expect(veilles.listForAccount).toHaveBeenCalledTimes(1);
    expect(el.querySelector('.nt__clock')?.textContent).toMatch(/^\d\d:\d\d$/);
    expect(el.querySelector('.nt__watches')?.textContent).toContain('Veille a');
    expect(night.awake()).toBeFalse();
    expect(el.querySelector('.nt--awake')).toBeNull();
    fixture.componentRef.setInput('ambient', { ...QUIET, hostDown: { clientKey: 'h-eden', name: 'Edenred' } });
    fixture.detectChanges();
    expect(night.awake()).toBeTrue();
    expect(el.querySelector('.nt__alerts')?.textContent).toContain('Poste muet : Edenred');
    night.tick(Date.now() + NIGHT_AWAKE_MS + 1);
    expect(night.awake()).toBeFalse();
    let exited = 0;
    night.exit.subscribe(() => exited++);
    (el.querySelector('.nt__exit') as HTMLButtonElement).click();
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    expect(exited).toBe(2);
    fixture.destroy();
  });
});
