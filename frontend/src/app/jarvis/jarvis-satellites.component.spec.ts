import { TestBed } from '@angular/core/testing';

import { HOLO_COLORS } from './stage3d/jarvis-motion';
import { JarvisWork } from './jarvis-works';
import {
  JarvisSatellitesComponent, MAX_COMPACT, MAX_SATELLITES, PHASE_COLORS, Satellite, labelName, satellitesOf, strongestPhase,
} from './jarvis-satellites.component';

const work = (id: string, name: string, phase: JarvisWork['phase']): JarvisWork => ({
  id, origin: 'DELEGATION', subjectId: 'w-1', subjectName: 'data-platform', chantier: null, name, label: name, phase,
  since: '2026-11-18T00:00:00Z', endedAt: null,
});

describe('F-270 / SF-270-05 — les satellites des travaux en fond', () => {
  it('fusionne les travaux lus et ceux que l’écran vient de lancer, sans doublon, au plus 12', () => {
    const list = satellitesOf([work('a', 'LDIC-325', 'EXECUTING')], [{ id: 'a', name: 'x' }, { id: 'b', name: 'agenor' }]);
    expect(list).toEqual([{ id: 'a', name: 'LDIC-325', phase: 'EXECUTING' }, { id: 'b', name: 'agenor', phase: 'THINKING' }]);
    const many = satellitesOf(Array.from({ length: 15 }, (_, i) => work(`w${i}`, `n${i}`, 'THINKING')), []);
    expect(many.length).toBe(MAX_SATELLITES);
    expect(many.at(-1)?.name).toBe('n14');
  });

  it('deux travaux du même sujet : chacun prend deux mots de son libellé, jamais une phrase', () => {
    const a = { ...work('a', 'facturation', 'THINKING'), label: 'tickets backlog facturation' };
    const b = { ...work('b', 'facturation', 'EXECUTING'), label: 'relance du pipeline de dev' };
    expect(satellitesOf([a, b], []).map((s) => s.name)).toEqual(['tickets backlog', 'relance pipeline']);
    expect(labelName('Le backlog : les tickets du sprint 9')).toBe('backlog tickets');
  });

  it('la teinte suit la phase : réfléchit violet, exécute or, attend votre oui cyan, fini vert, échec rouge', () => {
    expect(PHASE_COLORS.THINKING).toBe('#9B7BFF');
    expect(PHASE_COLORS.EXECUTING).toBe('#E8B44A');
    expect(HOLO_COLORS.exec).toBe('#E8B44A');
    expect(PHASE_COLORS.WAITING_YES).toBe(HOLO_COLORS.wait);
    expect(PHASE_COLORS.DONE).toBe(HOLO_COLORS.green);
    expect(PHASE_COLORS.FAILED).toBe(HOLO_COLORS.alert);
  });

  it('la phase la plus forte en cours : exécute avant réfléchit ; rien de vivant : aucune', () => {
    const s = (phase: Satellite['phase']): Satellite => ({ id: phase, name: phase, phase });
    expect(strongestPhase([s('THINKING'), s('EXECUTING'), s('DONE')])).toBe('EXECUTING');
    expect(strongestPhase([s('DONE'), s('FAILED')])).toBeNull();
  });

  it('dessine une perle et un nom court par travail, teintée ; résumé pour les lecteurs d’écran ; jamais une phrase',
    () => {
      const fixture = TestBed.createComponent(JarvisSatellitesComponent);
      fixture.componentRef.setInput('items', [{ id: 'a', name: 'LDIC-325', phase: 'EXECUTING' },
        { id: 'b', name: 'facturation', phase: 'WAITING_YES' }]);
      fixture.detectChanges();
      const el = fixture.nativeElement as HTMLElement;
      const names = [...el.querySelectorAll('.sat__name')].map((n) => n.textContent?.trim());
      expect(names).toEqual(['LDIC-325', 'facturation']);
      const body = el.querySelector('.sat__body--executing') as HTMLElement;
      expect(body.style.getPropertyValue('--sat-color')).toBe('#E8B44A');
      expect(el.querySelector('.sat')?.getAttribute('aria-hidden')).toBe('true');
      expect(el.querySelector('[role=status]')?.textContent).toContain('LDIC-325 : exécute');
    });

  it('téléphone : au plus 6, les plus récents ; aucun travail : rien n’est dessiné', () => {
    const fixture = TestBed.createComponent(JarvisSatellitesComponent);
    fixture.componentRef.setInput('compact', true);
    fixture.componentRef.setInput('items', Array.from({ length: 9 }, (_, i) => ({ id: `${i}`, name: `n${i}`,
      phase: 'THINKING' as const })));
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelectorAll('.sat__name').length).toBe(MAX_COMPACT);
    fixture.componentRef.setInput('items', []);
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('.sat')).toBeNull();
  });
});
