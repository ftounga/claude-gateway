import { TestBed } from '@angular/core/testing';

import { JarvisCardComponent } from './jarvis-card.component';
import { CARD_TYPES, JarvisCard } from './jarvis-card.models';
import {
  CARD_TEMPLATES, cardFreshness, cardTypesWithoutTemplate, resetCardTemplates, safeLink, templateFor, toneOf,
} from './jarvis-card-templates';

const NOW = Date.parse('2026-11-05T17:50:00Z'); // 18 h 50 à Paris

function card(patch: Partial<JarvisCard> = {}): JarvisCard {
  return {
    id: 'fait:etat:1', type: 'TICKET', title: 'PROJ-123 — Migration SSO', status: { word: 'En revue', tone: 'normal' },
    fields: [{ label: 'Statut', value: 'En revue' }, { label: 'Assigné', value: 'Rémi' }],
    link: 'https://jira.example.com/browse/PROJ-123', observedAt: '2026-11-05T17:40:00Z', source: 'tour',
    group: 'CAGIP', aliases: ['PROJ-123'], targeted: true, ...patch,
  };
}

describe('F-248 / SF-248-03 — les gabarits des pancartes', () => {
  afterEach(() => resetCardTemplates());

  it('garde de couverture : tout type a un gabarit ; un type inconnu prend GENERIQUE, jamais rien', () => {
    expect(cardTypesWithoutTemplate()).toEqual([]);
    expect(CARD_TYPES.length).toBe(11);
    expect(templateFor('NOUVEAU')).toBe(CARD_TEMPLATES.GENERIQUE);
    expect(templateFor(null).word).toBe('Fait');
  });

  it('ton : celui de la passerelle, un ton inconnu devient normal', () => {
    expect(toneOf(card({ status: { word: 'pipeline échoué', tone: 'alerte' } }))).toBe('alerte');
    expect(toneOf(card({ status: { word: 'x', tone: 'violet' as never } }))).toBe('normal');
    expect(toneOf(card({ status: null }))).toBe('normal');
  });

  it('fraîcheur : à l’instant, relevé 18 h 40, hier, jour de semaine ; plus de 24 h ⇒ ancien', () => {
    expect(cardFreshness('2026-11-05T17:45:00Z', NOW, true)).toEqual({ label: 'à l’instant', old: false });
    expect(cardFreshness('2026-11-05T17:40:00Z', NOW, false)).toEqual({ label: 'relevé 18 h 40', old: false });
    expect(cardFreshness('2026-11-04T17:40:00Z', NOW, false).label).toBe('relevé hier 18 h 40');
    const monday = cardFreshness('2026-11-02T17:40:00Z', NOW, false);
    expect(monday.label).toBe('relevé lun. 18 h 40');
    expect(monday.old).toBeTrue();
    expect(cardFreshness('2026-10-01T08:05:00Z', NOW, false).label).toMatch(/^relevé 1 oct\.? 10 h 05$/);
    expect(cardFreshness(null, NOW, true).label).toBe('');
  });

  it('lien rapide : http(s) ou route de l’application ; jamais javascript:, jamais //hôte', () => {
    expect(safeLink('https://gitlab.example.com/p/-/merge_requests/42')?.external).toBeTrue();
    expect(safeLink('/atelier/abc?chantier=3')).toEqual({ href: '/atelier/abc?chantier=3', external: false });
    expect(safeLink('javascript:alert(1)')).toBeNull();
    expect(safeLink('//evil.example.com')).toBeNull();
    expect(safeLink(null)).toBeNull();
  });
});

describe('F-248 / SF-248-03 — JarvisCardComponent', () => {
  function render(c: JarvisCard, opts: { flat?: boolean; connected?: boolean } = {}) {
    const fixture = TestBed.createComponent(JarvisCardComponent);
    fixture.componentRef.setInput('card', c);
    fixture.componentRef.setInput('now', NOW);
    fixture.componentRef.setInput('flat', opts.flat ?? false);
    fixture.componentRef.setInput('connected', opts.connected ?? false);
    fixture.detectChanges();
    return fixture;
  }

  it('titre, mot du statut écrit, champs, lien externe sûr, badge de fraîcheur ; un bouton « Ouvrir … »', () => {
    const fixture = render(card());
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('.jc__title')?.textContent).toContain('PROJ-123 — Migration SSO');
    expect(el.querySelector('.jc__status')?.textContent?.trim()).toBe('En revue');
    expect(el.querySelectorAll('.jc__field').length).toBe(2);
    expect(el.querySelector('.jc__fresh')?.textContent).toContain('relevé 18 h 40');
    expect(el.querySelector('.jc__kind')?.textContent).toContain('Ticket');
    const link = el.querySelector('a.jc__link') as HTMLAnchorElement;
    expect(link.target).toBe('_blank');
    expect(link.rel).toContain('noopener');
    expect(el.querySelector('button.jc__open')?.getAttribute('aria-label')).toBe('Ouvrir PROJ-123 — Migration SSO');
  });

  it('ancien : badge écrit et pancarte atténuée ; alerte : data-tone=alerte', () => {
    const fixture = render(card({ observedAt: '2026-11-03T10:00:00Z', status: { word: 'Incident', tone: 'alerte' } }));
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('.jc__old')?.textContent).toContain('ancien');
    expect(el.querySelector('.jc')?.classList).toContain('jc--old');
    expect(el.querySelector('.jc')?.getAttribute('data-tone')).toBe('alerte');
  });

  it('filet : une valeur à forme de secret est masquée à l’écran ; aucun lien dangereux rendu', () => {
    const fixture = render(card({ fields: [{ label: 'Note', value: 'token=abcdef123456' }], link: 'javascript:x' }));
    const el: HTMLElement = fixture.nativeElement;
    expect(el.textContent).not.toContain('abcdef123456');
    expect(el.querySelector('a.jc__link')).toBeNull();
  });

  it('toucher la pancarte émet « opened » ; le lien émet « linked » ; peau plate en 2D', () => {
    const fixture = render(card({ link: '/atelier/w1?chantier=2' }), { flat: true });
    const opened: JarvisCard[] = [];
    const linked: JarvisCard[] = [];
    fixture.componentInstance.opened.subscribe((c) => opened.push(c));
    fixture.componentInstance.linked.subscribe((c) => linked.push(c));
    const el: HTMLElement = fixture.nativeElement;
    (el.querySelector('button.jc__open') as HTMLButtonElement).click();
    const a = el.querySelector('a.jc__link') as HTMLAnchorElement;
    a.addEventListener('click', (e) => e.preventDefault());
    a.click();
    expect(opened.length).toBe(1);
    expect(linked.length).toBe(1);
    expect(a.textContent).toContain('Voir le chantier');
    expect(el.querySelector('.jc')?.classList).toContain('jc--flat');
  });
});
