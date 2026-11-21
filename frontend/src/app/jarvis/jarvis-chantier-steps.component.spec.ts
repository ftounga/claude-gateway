import { TestBed } from '@angular/core/testing';

import { JarvisChantierStepsComponent, STAR_COLORS, featuredChantier, starsOf } from './jarvis-chantier-steps.component';
import { JarvisWork } from './jarvis-works';
import { HOLO_COLORS } from './stage3d/jarvis-motion';

describe('F-271 / SF-271-05 — le chantier en constellation', () => {
  const work = (id: string, phase: JarvisWork['phase'], steps: JarvisWork['steps']): JarvisWork => ({
    id, origin: 'COURSE', subjectId: 'w-1', subjectName: 'data-platform', chantier: 13, name: 'LDIC-325',
    label: 'LDIC-325 — topic SNS', phase, since: '2026-11-21T08:00:00Z', endedAt: null, steps });
  const steps: JarvisWork['steps'] = [
    { name: 'SNS', state: 'DONE' }, { name: 'IAM', state: 'CURRENT' }, { name: 'Terraform', state: 'TODO' },
    { name: 'MR', state: 'BLOCKED' },
  ];

  it('le chantier montré : le vivant le plus récent qui a des étapes, sinon le plus récent fini ; aucun sinon', () => {
    expect(featuredChantier([])).toBeNull();
    expect(featuredChantier([work('a', 'EXECUTING', null)])).toBeNull();
    expect(featuredChantier([work('a', 'DONE', steps), work('b', 'THINKING', steps), work('c', 'DONE', steps)])?.id)
      .toBe('b');
    expect(featuredChantier([work('a', 'DONE', steps), work('c', 'DONE', steps)])?.id).toBe('c');
  });

  it('les étoiles sur un arc sous le cœur, de gauche à droite, 12 au plus', () => {
    const stars = starsOf(steps!);
    expect(stars.map((s) => s.name)).toEqual(['SNS', 'IAM', 'Terraform', 'MR']);
    expect(stars[0].x).toBeLessThan(stars[3].x);
    stars.forEach((s) => expect(s.y).toBeGreaterThan(50));
    expect(starsOf(Array.from({ length: 20 }, (_, i) => ({ name: `e${i}`, state: 'TODO' as const }))).length).toBe(12);
  });

  it('rendu : un nom court par étoile, teinte de son état (en cours = or), aucune phrase, résumé accessible', () => {
    const fixture = TestBed.createComponent(JarvisChantierStepsComponent);
    fixture.componentRef.setInput('work', work('a', 'EXECUTING', steps));
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const names = Array.from(el.querySelectorAll('.cst__name')).map((n) => n.textContent?.trim());
    expect(names).toEqual(['SNS', 'IAM', 'Terraform', 'MR']);
    expect(el.querySelector('.cst__star--current')?.getAttribute('style')).toContain(STAR_COLORS.CURRENT);
    expect(STAR_COLORS.CURRENT).toBe(HOLO_COLORS.exec);
    expect(el.querySelector('[role=status]')?.textContent).toContain('LDIC-325 : 1 sur 4');
    names.forEach((n) => expect((n ?? '').split(' ').length).toBeLessThanOrEqual(2));
    fixture.componentRef.setInput('work', null);
    fixture.detectChanges();
    expect(el.querySelector('.cst')).toBeNull();
  });
});
