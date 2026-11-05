import { TestBed } from '@angular/core/testing';

import { JarvisCardDetailComponent, compareRows } from './jarvis-card-detail.component';
import { cardPath } from './jarvis-card-client';
import { JarvisCard } from './jarvis-card.models';
import { cardVoiceCommand } from './jarvis-card-voice';

const NOW = Date.parse('2026-11-05T17:50:00Z');

function card(id: string, patch: Partial<JarvisCard> = {}): JarvisCard {
  return { id, type: 'TICKET', title: id, fields: [{ label: 'Statut', value: 'En revue' }], aliases: [], targeted: true,
    status: { word: 'En revue', tone: 'normal' }, observedAt: '2026-11-05T17:40:00Z', source: 'tour', ...patch };
}

describe('F-248 / SF-248-06 — les ordres vocaux des pancartes (liste fermée)', () => {
  it('ferme ça, montre le deuxième, la suite, compare le premier et le troisième, en grand', () => {
    expect(cardVoiceCommand('Ferme ça', 3)).toEqual({ type: 'close' });
    expect(cardVoiceCommand('Jarvis, montre le deuxième', 3)).toEqual({ type: 'select', index: 2 });
    expect(cardVoiceCommand('la suite', 9)).toEqual({ type: 'page' });
    expect(cardVoiceCommand('Compare le premier et le troisième', 3)).toEqual({ type: 'compare', a: 1, b: 3 });
    expect(cardVoiceCommand('en grand', 1)).toEqual({ type: 'detail' });
  });

  it('rien sans jeu, rien hors bornes, une phrase plus riche part au cerveau rapide', () => {
    expect(cardVoiceCommand('ferme ça', 0)).toBeNull();
    expect(cardVoiceCommand('montre le sixième', 3)).toBeNull();
    expect(cardVoiceCommand('compare le premier et le premier', 3)).toBeNull();
    expect(cardVoiceCommand('montre celle de Rémi', 3)).toBeNull();
  });
});

describe('F-248 / SF-248-06 — le plein écran et la comparaison', () => {
  it('compareRows : champs alignés par libellé, différences marquées', () => {
    const rows = compareRows([{ label: 'Statut', value: 'En revue' }, { label: 'Assigné', value: 'Rémi' }],
      [{ label: 'Statut', value: 'Bloqué' }, { label: 'Assigné', value: 'Rémi' }, { label: 'Bloqué par', value: 'X-1' }]);
    expect(rows.map((r) => [r.label, r.differs])).toEqual([['Statut', true], ['Assigné', false], ['Bloqué par', true]]);
    expect(rows[2].a).toBe('—');
  });

  it('le chemin garde les deux-points et encode le reste', () => {
    expect(cardPath('fait:envoi:abc:2')).toBe('/api/jarvis/cards/fait:envoi:abc:2');
    expect(cardPath('fait:etat:a b')).toBe('/api/jarvis/cards/fait:etat:a%20b');
  });

  it('plein écran : tous les champs du détail, la valeur précédente, la source, le sujet ; secret masqué', () => {
    const fixture = TestBed.createComponent(JarvisCardDetailComponent);
    fixture.componentRef.setInput('card', card('PROJ-123', { link: 'https://jira.example.com/browse/PROJ-123' }));
    fixture.componentRef.setInput('now', NOW);
    fixture.componentRef.setInput('detail', {
      card: card('PROJ-123'), subjectName: 'Edenred', chantier: 3,
      fields: [{ label: 'Statut', value: 'En revue' }, { label: 'Note', value: 'token=abcdef123456' }],
      previous: { value: 'En cours', at: '2026-11-04T16:42:00Z' },
    });
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelectorAll('.jcd__fields dt').length).toBe(2);
    expect(el.querySelector('.jcd__prev')?.textContent).toContain('Passé de En cours à En revue — hier 17 h 42');
    expect(el.textContent).toContain('vu pendant un tour');
    expect(el.textContent).toContain('Sujet Edenred, chantier 3');
    expect(el.textContent).not.toContain('abcdef123456');
    expect((el.querySelector('a.jcd__btn') as HTMLAnchorElement).rel).toContain('noopener');
    expect(el.textContent).not.toContain('grand écran');
  });

  it('comparaison : deux colonnes, « diffère » écrit ; grand écran proposé si un cockpit écoute', () => {
    const fixture = TestBed.createComponent(JarvisCardDetailComponent);
    fixture.componentRef.setInput('card', card('A'));
    fixture.componentRef.setInput('other', card('B', { fields: [{ label: 'Statut', value: 'Bloqué' }] }));
    fixture.componentRef.setInput('canBig', true);
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelectorAll('.jcd__compare tbody tr').length).toBe(1);
    expect(el.querySelector('.jcd__mark')?.textContent).toContain('diffère');
    const big: JarvisCard[] = [];
    fixture.componentInstance.big.subscribe((c) => big.push(c));
    (Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.includes('grand écran')) as HTMLButtonElement)
      .click();
    expect(big.length).toBe(1);
  });
});
