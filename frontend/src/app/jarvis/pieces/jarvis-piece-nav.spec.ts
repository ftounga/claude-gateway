import { Piece } from './jarvis-piece.models';
import { MAX_NAV_ITEMS, NavAction, NavScope, PieceNav, initialNav, reduceNav } from './jarvis-piece-nav';
import { pieceVoiceCommand } from './jarvis-piece-voice';
import { PIECE_VIEWERS, kindsWithoutViewer, registerViewer, resetViewers, viewerFor } from './jarvis-piece-viewers';
import { SCREEN_MAX_CHARS, screenBlock } from './jarvis-piece-screen';

const SCOPE: NavScope = { subjectId: 's1', chantier: 3, category: 'ENTREE', label: 'Entrées du chantier 3 — Edenred' };

function piece(id: string, kind: Piece['kind'], title: string, meta: Piece['meta'] = {}): Piece {
  return { id, kind, title, category: 'ENTREE', at: '2026-11-03T09:42:00Z', meta };
}

const PIECES: Piece[] = [
  piece('dep:1', 'IMAGE', "Capture de l'erreur 502"),
  piece('video:2@v1', 'VIDEO', 'Démo du déploiement', {
    durationS: 114, chapters: [{ t: 0, title: 'Introduction' }, { t: 40, title: 'Déploiement' }, { t: 90, title: 'Bilan' }],
  }),
  piece('dep:3', 'PDF', 'Contrat de service', { pages: 12 }),
  piece('need:4', 'TEXT', 'Besoin initial du chantier 3'),
];

function run(state: PieceNav, ...actions: NavAction[]): PieceNav {
  return actions.reduce((s, a) => reduceNav(s, a).state, state);
}

function opened(): PieceNav {
  return run(initialNav(), { type: 'openGallery', scope: SCOPE, pieces: PIECES });
}

describe('Jarvis — navigation parmi les pièces (F-237 / SF-237-04)', () => {
  afterEach(() => resetViewers());

  it('ouvre une galerie : cartes indexées 1..n, rien de sélectionné', () => {
    const s = opened();
    expect(s.level).toBe('GALERIE');
    expect(s.items.map(i => i.index)).toEqual([1, 2, 3, 4]);
    expect(s.selected).toBeNull();
    expect(s.history).toEqual(['UNIVERS']);
  });

  it('au-delà de 24 cartes : « +n »', () => {
    const many = Array.from({ length: 30 }, (_, i) => piece(`dep:${i}`, 'IMAGE', `Image ${i}`));
    const s = run(initialNav(), { type: 'openGallery', scope: SCOPE, pieces: many });
    expect(s.items.length).toBe(MAX_NAV_ITEMS);
    expect(s.more).toBe(6);
  });

  it('le banc de séquences §9 : chaque pas donne l’état attendu', () => {
    let s = opened();
    s = reduceNav(s, { type: 'select', ref: 2 }).state; // « la deuxième »
    expect([s.level, s.selected, s.playing, s.time]).toEqual(['PIECE', 2, true, 0]);
    s = reduceNav(s, { type: 'pause' }).state;
    expect(s.playing).toBeFalse();
    s = reduceNav(s, { type: 'seek', chapter: 'déploiement' }).state;
    expect([s.level, s.chapter, s.time]).toEqual(['INSTANT', 2, 40]);
    s = reduceNav(s, { type: 'next' }).state; // « la suivante »
    expect([s.level, s.selected, s.time, s.page]).toEqual(['PIECE', 3, null, 1]);
    s = reduceNav(s, { type: 'back' }).state;
    expect([s.level, s.selected]).toEqual(['GALERIE', null]);
    s = reduceNav(s, { type: 'up' }).state;
    expect(s.level).toBe('CHANTIER');
    expect(s.scope?.chantier).toBe(3);
  });

  it('sélection par identifiant ou titre approché (unique), sinon NOT_FOUND', () => {
    expect(reduceNav(opened(), { type: 'select', ref: 'dep:3' }).state.selected).toBe(3);
    expect(reduceNav(opened(), { type: 'select', ref: 'celle sur le deploiement' }).reason).toBe('NOT_FOUND');
    expect(reduceNav(opened(), { type: 'select', ref: 'déploiement' }).state.selected).toBe(2);
    expect(reduceNav(opened(), { type: 'select', ref: 'e' }).reason).toBe('NOT_FOUND'); // ambigu
  });

  it('action invalide : état inchangé, raison rendue', () => {
    const s = opened();
    expect(reduceNav(s, { type: 'select', ref: 9 })).toEqual({ state: s, ok: false, reason: 'OUT_OF_RANGE' });
    const image = run(s, { type: 'select', ref: 1 });
    expect(reduceNav(image, { type: 'page', delta: 1 }).reason).toBe('NOT_APPLICABLE');
    expect(reduceNav(image, { type: 'play' }).reason).toBe('NOT_APPLICABLE');
    expect(reduceNav(image, { type: 'seek', t: 3 }).reason).toBe('NOT_APPLICABLE');
    expect(reduceNav(image, { type: 'prev' }).reason).toBe('OUT_OF_RANGE');
    const last = run(s, { type: 'select', ref: 4 });
    expect(reduceNav(last, { type: 'next' }).reason).toBe('OUT_OF_RANGE');
    expect(reduceNav(initialNav(), { type: 'next' }).reason).toBe('NO_GALLERY');
    expect(reduceNav(initialNav(), { type: 'select', ref: 1 }).reason).toBe('NO_GALLERY');
    expect(reduceNav(initialNav(), { type: 'back' }).reason).toBe('NOT_APPLICABLE');
    expect(reduceNav(initialNav(), { type: 'up' }).reason).toBe('NOT_APPLICABLE');
    expect(reduceNav(initialNav(), { type: 'fullscreen' }).reason).toBe('NOT_APPLICABLE');
  });

  it('PDF : pages bornées, la page 4 passe au niveau PAGE, retour à la pièce', () => {
    let s = run(opened(), { type: 'select', ref: 3 });
    s = reduceNav(s, { type: 'page', n: 4 }).state;
    expect([s.level, s.page]).toEqual(['PAGE', 4]);
    expect(reduceNav(s, { type: 'page', n: 13 }).reason).toBe('OUT_OF_RANGE');
    s = reduceNav(s, { type: 'page', delta: -1 }).state;
    expect(s.page).toBe(3);
    s = reduceNav(s, { type: 'back' }).state;
    expect(s.level).toBe('PIECE');
  });

  it('vidéo : aller à un temps, chapitre déduit ; au-delà de la durée refusé', () => {
    let s = run(opened(), { type: 'select', ref: 2 });
    s = reduceNav(s, { type: 'seek', t: 95 }).state;
    expect([s.time, s.chapter]).toEqual([95, 3]);
    expect(reduceNav(s, { type: 'seek', t: 500 }).reason).toBe('OUT_OF_RANGE');
    expect(reduceNav(s, { type: 'seek', chapter: 7 }).reason).toBe('OUT_OF_RANGE');
    expect(reduceNav(s, { type: 'seek', chapter: 1 }).state.time).toBe(0);
  });

  it('zoom ×1..×8, déplacement seulement zoomé, cible nommée, retour à ×1 efface tout', () => {
    let s = run(opened(), { type: 'select', ref: 1 });
    expect(reduceNav(s, { type: 'pan', dx: 1, dy: 0 }).reason).toBe('NOT_APPLICABLE');
    s = run(s, { type: 'zoom', step: '+' }, { type: 'zoom', step: '+' }, { type: 'zoom', step: '+' },
      { type: 'zoom', step: '+' });
    expect(s.zoom).toBe(8);
    s = run(s, { type: 'pan', dx: 10, dy: -5 }, { type: 'zoom', target: 'coin en haut à droite' });
    expect(s.pan).toEqual({ x: 10, y: -5 });
    expect(s.target).toBe('coin en haut à droite');
    expect(reduceNav(s, { type: 'zoom', value: 9 }).reason).toBe('OUT_OF_RANGE');
    s = reduceNav(s, { type: 'zoom', step: 'reset' }).state;
    expect([s.zoom, s.pan.x, s.target]).toEqual([1, 0, null]);
    const text = run(opened(), { type: 'select', ref: 4 });
    expect(reduceNav(text, { type: 'zoom', step: '+' }).reason).toBe('NOT_APPLICABLE');
  });

  it('fermer revient à l’univers ; plein écran bascule', () => {
    let s = run(opened(), { type: 'select', ref: 1 }, { type: 'fullscreen' });
    expect(s.fullscreen).toBeTrue();
    s = reduceNav(s, { type: 'close' }).state;
    expect(s).toEqual(initialNav());
  });

  it('le réducteur est pur : il ne modifie jamais l’état reçu', () => {
    const s = opened();
    const copy = JSON.parse(JSON.stringify(s));
    reduceNav(s, { type: 'select', ref: 2 });
    reduceNav(s, { type: 'close' });
    expect(JSON.parse(JSON.stringify(s))).toEqual(copy);
  });

  it('toucher et voix produisent le même état (mêmes actions)', () => {
    const touched = run(opened(), { type: 'select', ref: 2 }, { type: 'pause' }, { type: 'next' });
    const said = ['la deuxième', 'pause', 'suivante'].reduce((s, phrase) => {
      const action = pieceVoiceCommand(phrase, s);
      expect(action).not.toBeNull();
      return reduceNav(s, action!).state;
    }, opened());
    expect(said).toEqual(touched);
  });
});

describe('Jarvis — ordres vocaux locaux (F-237 / SF-237-04)', () => {
  const g = opened();

  it('reconnaît la liste fermée, phrase entière, accents et casse ignorés', () => {
    expect(pieceVoiceCommand('Suivante.', g)).toEqual({ type: 'next' });
    expect(pieceVoiceCommand('la précédente', g)).toEqual({ type: 'prev' });
    expect(pieceVoiceCommand('La troisième', g)).toEqual({ type: 'select', ref: 3 });
    expect(pieceVoiceCommand('la 3', g)).toEqual({ type: 'select', ref: 3 });
    expect(pieceVoiceCommand('numéro 12', g)).toEqual({ type: 'select', ref: 12 });
    expect(pieceVoiceCommand('la deux', g)).toEqual({ type: 'select', ref: 2 });
    expect(pieceVoiceCommand('Pause', g)).toEqual({ type: 'pause' });
    expect(pieceVoiceCommand('Arrête la vidéo', g)).toEqual({ type: 'pause' });
    expect(pieceVoiceCommand('reprends', g)).toEqual({ type: 'play' });
    expect(pieceVoiceCommand('Reviens à la galerie', g)).toEqual({ type: 'back' });
    expect(pieceVoiceCommand('Remonte au chantier', g)).toEqual({ type: 'up' });
    expect(pieceVoiceCommand('page suivante', g)).toEqual({ type: 'page', delta: 1 });
    expect(pieceVoiceCommand('page 4', g)).toEqual({ type: 'page', n: 4 });
    expect(pieceVoiceCommand('page quatre', g)).toEqual({ type: 'page', n: 4 });
    expect(pieceVoiceCommand('plus près', g)).toEqual({ type: 'zoom', step: '+' });
    expect(pieceVoiceCommand('dézoome', g)).toEqual({ type: 'zoom', step: '-' });
    expect(pieceVoiceCommand("C'est bon", g)).toEqual({ type: 'close' });
    expect(pieceVoiceCommand('Jarvis, suivante', g)).toEqual({ type: 'next' });
  });

  it('tout le reste part à l’IA (null) : ambigu, plus riche, ou aucune galerie ouverte', () => {
    expect(pieceVoiceCommand('celle sur le déploiement', g)).toBeNull();
    expect(pieceVoiceCommand('le cours 3', g)).toBeNull();
    expect(pieceVoiceCommand('va au moment où il parle de Terraform', g)).toBeNull();
    expect(pieceVoiceCommand('suivante puis zoome', g)).toBeNull();
    expect(pieceVoiceCommand('la belle', g)).toBeNull();
    expect(pieceVoiceCommand('3', g)).toBeNull();
    expect(pieceVoiceCommand('', g)).toBeNull();
    expect(pieceVoiceCommand(null, g)).toBeNull();
    expect(pieceVoiceCommand('suivante', initialNav())).toBeNull();
  });
});

describe('Jarvis — bloc « ## Écran » (F-237 / SF-237-04)', () => {
  it('univers sans galerie, présentation sans pièce', () => {
    expect(screenBlock(initialNav())).toBe('## Écran\nÉcran : univers');
    expect(screenBlock(opened(), true)).toContain('aucune pièce');
    expect(screenBlock(opened(), true)).not.toContain('Capture');
  });

  it('liste les cartes et l’état de la pièce sélectionnée', () => {
    const s = run(opened(), { type: 'select', ref: 2 }, { type: 'seek', chapter: 2 });
    const block = screenBlock(s);
    expect(block.split('\n')[1]).toBe('Galerie : Entrées du chantier 3 — Edenred (4 pièces) ; sélection : 2');
    expect(block).toContain("1. image — « Capture de l'erreur 502 » — mardi 10:42");
    expect(block).toContain('2. vidéo — « Démo du déploiement » — mardi 10:42 — en lecture 0:40 / 1:54 — '
      + 'chapitre 2 « Déploiement »');
    expect(block).toContain('3. PDF — « Contrat de service » — mardi 10:42 — 12 pages');
  });

  it('jamais plus de 1 500 caractères, titres coupés à 60', () => {
    const many = Array.from({ length: 24 }, (_, i) => piece(`dep:${i}`, 'IMAGE', 'x'.repeat(200) + i));
    const s = run(initialNav(), { type: 'openGallery', scope: SCOPE, pieces: many });
    const block = screenBlock(s);
    expect(block.length).toBeLessThanOrEqual(SCREEN_MAX_CHARS);
    expect(block).not.toContain('x'.repeat(61));
    expect(block.endsWith('…')).toBeTrue();
  });
});

describe('Jarvis — registre des visionneuses (F-237 / SF-237-04)', () => {
  afterEach(() => resetViewers());

  it('garde : tout type a une visionneuse ; un type inconnu tombe sur la fiche', () => {
    expect(kindsWithoutViewer()).toEqual([]);
    expect(viewerFor('HOLOGRAMME').name).toBe('fiche');
    expect(viewerFor(null).name).toBe('fiche');
  });

  it('point d’extension : la visionneuse d’architecture se remplace sans toucher au reste', () => {
    const before = PIECE_VIEWERS.IMAGE;
    const previous = registerViewer('ARCHITECTURE', { name: 'architecture-3d', paged: false, timed: false,
      zoomable: true });
    expect(previous.name).toBe('schema-svg');
    expect(viewerFor('ARCHITECTURE').name).toBe('architecture-3d');
    expect(PIECE_VIEWERS.IMAGE).toBe(before);
    expect(viewerFor('DIAGRAM').name).toBe('schema-svg');
  });
});
