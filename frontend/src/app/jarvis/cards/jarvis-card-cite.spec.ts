import { dispatch } from '../jarvis-fast-brain';
import { CardShowing, cardsCited, technicalKeys } from './jarvis-card-cite';
import { CardsEvent, JarvisCard } from './jarvis-card.models';

function card(id: string, title: string, aliases: string[], targeted = false): JarvisCard {
  return { id, type: 'TICKET', title, fields: [], aliases, targeted, status: null };
}

const CATALOGUE: JarvisCard[] = [
  card('fait:etat:1', 'PROJ-123 — Migration SSO', ['PROJ-123', 'Migration SSO']),
  card('fait:etat:2', 'MR projet!42', ['projet!42']),
  card('fait:personne:remi', 'Rémi Martin', ['Rémi Martin', 'remi']),
  card('fait:personne:remi2', 'Rémi Durand', ['Rémi Durand', 'remi']),
  card('fait:etat:3', 'api 2.14.1 en prod', ['api 2.14.1']),
];

describe('F-248 / SF-248-05 — citer = montrer (rapprocheur pur)', () => {
  it('clés techniques reconnues telles quelles : PROJ-123, !42, #17, versions', () => {
    expect(technicalKeys('Le ticket PROJ-123 et la MR !42 (#17), version v2.14.1')).toEqual(
      jasmine.arrayWithExactContents(['proj-123', '!42', '#17', '2.14.1']));
  });

  it('une clé, un alias à la frontière de mot, sans casse ni accents', () => {
    expect(cardsCited('Le ticket proj-123 est en revue.', CATALOGUE)).toEqual(['fait:etat:1']);
    expect(cardsCited('La migration sso avance bien.', CATALOGUE)).toEqual(['fait:etat:1']);
    expect(cardsCited('La MR !42 est fusionnée.', CATALOGUE)).toEqual(['fait:etat:2']);
    expect(cardsCited('L’api est en 2.14.1.', CATALOGUE)).toEqual(['fait:etat:3']);
    expect(cardsCited('Rémi Martin attend votre oui.', CATALOGUE)).toEqual(['fait:personne:remi']);
  });

  it('prudence : un alias qui désigne deux pancartes n’allume rien ; un mot courant n’allume rien', () => {
    expect(cardsCited('Remi a répondu.', CATALOGUE)).toEqual([]);
    expect(cardsCited('Les tests sont verts.', [card('x', 'Tests', ['tests'])])).toEqual([]);
    expect(cardsCited('Rien de neuf, Monsieur.', CATALOGUE)).toEqual([]);
  });

  it('ordinaux d’un jeu déjà ouvert : « le deuxième »', () => {
    expect(cardsCited('Et le deuxième est bloqué.', CATALOGUE, ['fait:etat:1', 'fait:etat:2'])).toEqual(['fait:etat:2']);
    expect(cardsCited('Et le deuxième est bloqué.', CATALOGUE)).toEqual([]);
  });
});

describe('F-248 / SF-248-05 — le jeu d’une réponse au fil de la voix (D2, D10)', () => {
  const event = (cards: JarvisCard[]): CardsEvent => ({ responseId: 'r1', targeted: cards.some((c) => c.targeted), cards });

  it('rien avant la première phrase jouée, même ciblé (K2)', () => {
    const s = new CardShowing(1);
    s.receive(event([card('a', 'PROJ-1 — A', ['PROJ-1'], true)]));
    expect(s.visible()).toEqual([]);
    s.playing('Voici vos tickets.', 1000);
    expect(s.visible().map((c) => c.id)).toEqual(['a']);
    expect(s.lag(1140)).toBe(140);
  });

  it('lecture d’office : seules les citées, à la phrase qui les nomme ; catalogue reçu après la phrase', () => {
    const s = new CardShowing(1);
    expect(s.playing('Le ticket PROJ-123 est bloqué.', 0)).toEqual([]);
    s.receive(event(CATALOGUE));
    expect(s.visible().map((c) => c.id)).toEqual(['fait:etat:1']);
    expect(s.playing('Et la MR !42 attend.', 500)).toEqual(['fait:etat:2']);
    expect(s.visible().map((c) => c.id)).toEqual(['fait:etat:1', 'fait:etat:2']);
    expect(s.counts()).toEqual({ targeted: 0, cited: 2 });
  });

  it('l’événement SSE « cards » est relayé à l’écran ; un « cards » malformé est ignoré', () => {
    const got: CardsEvent[] = [];
    const handlers = { onText: () => {}, onDelegated: () => {}, onFallback: () => {}, onDone: () => {},
      onCards: (e: CardsEvent) => got.push(e) };
    dispatch('event:cards\ndata:{"responseId":"r1","targeted":false,"cards":[]}', handlers);
    dispatch('event:cards\ndata:{"cards":"x"}', handlers);
    expect(got.length).toBe(1);
    expect(got[0].responseId).toBe('r1');
  });
});
