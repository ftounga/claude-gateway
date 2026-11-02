import { Piece } from './jarvis-piece.models';
import { STORY_EXCERPT_S, StoryHooks, StoryRunner, storyExcerpt, storyOrder, storyPhrase } from './jarvis-piece-story';

function piece(id: string, category: Piece['category'], kind: Piece['kind'], title: string, extra: Partial<Piece> = {}): Piece {
  return { id, category, kind, title, at: '2026-11-03T09:00:00Z', ...extra };
}

const NEED = piece('need:1', 'ENTREE', 'TEXT', 'Besoin', { caption: 'Corrige l’erreur 502 du portail.', at: '2026-11-03T08:00:00Z' });
const DEP = piece('dep:1', 'ENTREE', 'IMAGE', 'capture-502.png', { caption: 'Voici la capture de l’erreur.' });
const PROOF4 = piece('proof:b', 'PREUVE', 'IMAGE', 'Santé', { caption: 'Étape 4. 1 commande, toutes réussies', meta: { step: 'Étape 4' } });
const PROOF2 = piece('proof:a', 'PREUVE', 'IMAGE', 'Build', { meta: { step: 'Étape 2' }, at: '2026-11-03T12:00:00Z' });
const SEND = piece('send:1', 'PREUVE', 'TEXT', 'Envoi', { caption: 'Portail rétabli — à Zahi' });
const VIDEO = piece('video:v@v1', 'PRODUCTION', 'VIDEO', 'Démo', {
  caption: 'Le déploiement sur EKS', meta: { durationS: 114, chapters: [{ t: 0, title: 'Intro' }, { t: 40, title: 'Déploiement' }] },
});

/** Des crochets factices : la voix se termine quand le test le décide. */
function hooks(log: string[]): StoryHooks & { finish(): Promise<void> } {
  let pending: (() => void) | null = null;
  return {
    select: (i) => log.push(`select ${i}`),
    say: (phrase) => {
      log.push(`say ${phrase}`);
      return new Promise<void>((resolve) => (pending = resolve));
    },
    excerpt: async (i, from, seconds) => {
      log.push(`excerpt ${i} ${from} ${seconds}`);
    },
    cut: () => {
      log.push('cut');
      pending?.();
    },
    finish: async () => {
      const p = pending;
      pending = null;
      p?.();
      await new Promise((r) => setTimeout(r, 0));
    },
  };
}

describe('Jarvis — le diaporama narré (F-237 / SF-237-10)', () => {
  it('ordre du récit : besoin, entrées, preuves par étape, productions, envois', () => {
    expect(storyOrder([SEND, VIDEO, PROOF4, DEP, PROOF2, NEED]).map((p) => p.id))
      .toEqual(['need:1', 'dep:1', 'proof:a', 'proof:b', 'video:v@v1', 'send:1']);
  });

  it('chaque phrase vient de ce qui est écrit ; sans description : titre et date, jamais un contenu inventé', () => {
    expect(storyPhrase(NEED, true)).toBe('Au départ, Corrige l’erreur 502 du portail.');
    expect(storyPhrase(DEP, false)).toBe('Vous m’aviez fourni l’image « capture-502.png » : Voici la capture de l’erreur.');
    expect(storyPhrase(PROOF2, false)).toMatch(/^Preuve : « Build » du mardi 3 novembre\.$/);
    expect(storyPhrase(SEND, false)).toBe('Envoi : Portail rétabli — à Zahi.');
    expect(storyPhrase({ ...DEP, chantierLink: 'WINDOW' }, false)).toContain('rattaché d’après la date');
  });

  it('extrait d\'une vidéo : le chapitre utile, au plus 15 s ; rien pour une image', () => {
    expect(storyExcerpt(VIDEO)).toEqual({ from: 40, seconds: STORY_EXCERPT_S });
    expect(storyExcerpt(DEP)).toBeNull();
  });

  it('la pièce suivante arrive à la FIN de la phrase — jamais avant', async () => {
    const log: string[] = [];
    const h = hooks(log);
    const runner = new StoryRunner([NEED, VIDEO], h);
    const done = runner.run();
    await new Promise((r) => setTimeout(r, 0));
    expect(log).toEqual(['select 1', 'say Au départ, Corrige l’erreur 502 du portail.']);
    await h.finish();
    expect(log.slice(2)).toEqual(['select 2', 'say J’ai produit la vidéo « Démo » : Le déploiement sur EKS.']);
    await h.finish();
    expect(await done).toBe('done');
    expect(log[log.length - 1]).toBe(`excerpt 2 40 ${STORY_EXCERPT_S}`);
  });

  it('« suivante » coupe et saute ; « pause » fige puis « reprends » ; « ferme » arrête', async () => {
    const log: string[] = [];
    const h = hooks(log);
    const runner = new StoryRunner([NEED, DEP, PROOF4], h);
    const done = runner.run();
    await new Promise((r) => setTimeout(r, 0));
    runner.next();
    await new Promise((r) => setTimeout(r, 0));
    expect(log).toContain('cut');
    expect(log).toContain('select 2');
    runner.pause();
    await h.finish();
    expect(runner.status).toBe('paused');
    expect(log.filter((l) => l.startsWith('select')).length).toBe(2);
    runner.play();
    await new Promise((r) => setTimeout(r, 0));
    expect(log.filter((l) => l === 'select 2').length).toBe(2); // la même pièce reprend
    runner.stop();
    expect(await done).toBe('stopped');
  });
});
