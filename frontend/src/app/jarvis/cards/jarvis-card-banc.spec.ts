import { TestBed } from '@angular/core/testing';

import { JarvisPieceGalleryComponent } from '../pieces/jarvis-piece-gallery.component';
import { JarvisPieceStore } from '../pieces/jarvis-piece-store';
import { BANC_CARDS, bancCardDetail, stageBancCards } from './jarvis-card-banc';
import { CardShowing, cardsCited } from './jarvis-card-cite';
import { CardsEvent, JarvisCard } from './jarvis-card.models';

/**
 * F-248 / SF-248-10 — **le banc PANCARTES, côté écran** (cadrage §8) : 40 scénarios déterministes rejoués sans modèle.
 * Portes : 0 pancarte hors catalogue, 100 % des citées montrées, 0 avant le premier mot, présentation vide ; mise en
 * page sans débordement à 1366 × 768 et 360 × 740.
 */

const PREFIXES = ['PROJ', 'EDN', 'OPS', 'SEC', 'INFRA'];

function card(i: number, targeted: boolean): JarvisCard {
  const key = `${PREFIXES[i % PREFIXES.length]}-${100 + i}`;
  return { id: `fait:etat:b.${i}`, type: 'TICKET', title: `${key} — Sujet ${i}`, aliases: [key], targeted,
    status: { word: 'En revue', tone: 'normal' }, fields: [{ label: 'Statut', value: 'En revue' }],
    observedAt: new Date().toISOString(), group: i % 2 ? 'CAGIP' : 'Edenred' };
}

interface Scenario {
  n: number;
  catalogue: JarvisCard[];
  sentences: string[];
  cited: string[];
  targeted: boolean;
}

/** 40 scénarios : tailles 1 à 12, lecture ciblée un sur trois, de 0 à 3 citées par phrase, un alias ambigu sur cinq. */
function scenarios(): Scenario[] {
  const out: Scenario[] = [];
  for (let n = 0; n < 40; n++) {
    const size = 1 + (n % 12);
    const targeted = n % 3 === 0;
    const catalogue = Array.from({ length: size }, (_, i) => card(n * 20 + i, targeted));
    const citedCards = catalogue.filter((_, i) => i % 3 === n % 3).slice(0, 3);
    const sentences = ['Voici ce que je sais, Monsieur.',
      ...citedCards.map((c) => `Le ${c.aliases[0]} est en revue.`)];
    if (n % 5 === 4 && size >= 2) {
      catalogue[0] = { ...catalogue[0], aliases: [...catalogue[0].aliases, 'migration'] };
      catalogue[1] = { ...catalogue[1], aliases: [...catalogue[1].aliases, 'migration'] };
      sentences.push('La migration avance.');
    }
    out.push({ n, catalogue, sentences, cited: citedCards.map((c) => c.id), targeted });
  }
  return out;
}

describe('F-248 / SF-248-10 — banc PANCARTES (écran, 40 scénarios)', () => {
  const all = scenarios();

  it('40 scénarios rejoués', () => {
    expect(all.length).toBe(40);
  });

  for (const s of all) {
    it(`scénario ${s.n} : ${s.catalogue.length} au catalogue, ${s.cited.length} citée(s)${s.targeted ? ', ciblé' : ''}`,
      () => {
        const showing = new CardShowing(s.n);
        const event: CardsEvent = { responseId: `r${s.n}`, targeted: s.targeted, cards: s.catalogue };
        showing.receive(event);
        // 0 avant le premier mot
        expect(showing.visible()).toEqual([]);
        let t = 1000;
        for (const sentence of s.sentences) {
          showing.playing(sentence, t);
          t += 800;
        }
        const visible = showing.visible().map((c) => c.id);
        const ids = new Set(s.catalogue.map((c) => c.id));
        // 0 pancarte hors catalogue
        expect(visible.every((id) => ids.has(id))).toBeTrue();
        // 100 % des citées montrées (lecture d'office) ; tout le jeu si la lecture est ciblée
        expect(s.cited.every((id) => visible.includes(id))).toBeTrue();
        if (s.targeted) {
          expect(visible.length).toBe(s.catalogue.length);
        } else {
          expect(visible.length).toBe(s.cited.length); // l'alias ambigu n'a rien allumé
        }
      });
  }

  it('prudence : une phrase sans citation n’allume rien ; présentation : le jeu est refusé', () => {
    expect(cardsCited('Rien de neuf, Monsieur.', BANC_CARDS)).toEqual([]);
    const store = TestBed.inject(JarvisPieceStore);
    store.reset();
    store.presentation.set(true);
    expect(store.showCards('r', BANC_CARDS).ok).toBeFalse();
    expect(store.open()).toBeFalse();
    store.presentation.set(false);
  });
});

describe('F-248 / SF-248-10 — banc de mise en page (K4 : aucun débordement)', () => {
  function render(width: number, height: number, scene: string, threeD: boolean): HTMLElement {
    const store = TestBed.inject(JarvisPieceStore);
    store.reset();
    const fixture = TestBed.createComponent(JarvisPieceGalleryComponent);
    const host: HTMLElement = fixture.nativeElement;
    const frame = document.createElement('div');
    frame.style.cssText = `position: relative; width: ${width}px; height: ${height}px; overflow: auto;`;
    document.body.appendChild(frame);
    frame.appendChild(host);
    fixture.componentRef.setInput('threeD', threeD);
    stageBancCards(store, scene);
    fixture.detectChanges();
    return frame;
  }

  for (const [w, h] of [[1366, 768], [360, 740]] as const) {
    for (const scene of ['pancartes-un', 'pancartes-eventail', 'pancartes-mur']) {
      it(`${w} × ${h} — ${scene} : aucune barre de défilement horizontale`, () => {
        const frame = render(w, h, scene, w > 400);
        try {
          expect(frame.querySelectorAll('app-jarvis-card').length).toBeGreaterThan(0);
          expect(frame.scrollWidth).toBeLessThanOrEqual(frame.clientWidth + 1);
        } finally {
          frame.remove();
        }
      });
    }
  }

  it('les scènes du banc posent le plein écran et la comparaison (aucun réseau)', () => {
    expect(bancCardDetail('pancartes-plein-ecran')?.detail?.previous?.value).toBe('En cours');
    expect(bancCardDetail('pancartes-comparer')?.other).not.toBeNull();
    expect(bancCardDetail('pancartes-un')).toBeNull();
  });
});
