import {
  ArchNav, ORBIT_EL_MAX, TURN_STEP, archScreenLine, archVoiceRequest, defaultArchNav, reduceArch, trivialArchOp,
} from './jarvis-piece-arch';
import { initialNav, reduceNav } from './jarvis-piece-nav';
import { pieceVoiceCommand } from './jarvis-piece-voice';
import { screenBlock, SCREEN_MAX_CHARS } from './jarvis-piece-screen';
import { showPlan } from './jarvis-piece-show';
import { Piece } from './jarvis-piece.models';

const ARCH: Piece = { id: 'diag:1', category: 'PRODUCTION', kind: 'ARCHITECTURE', title: '3-tiers multi-AZ' };
const VIDEO: Piece = { id: 'video:1', category: 'PREUVE', kind: 'VIDEO', title: 'Démo' };

function opened(pieces: Piece[] = [ARCH, VIDEO], select = 1) {
  const gallery = reduceNav(initialNav(), { type: 'openGallery', scope: { subjectId: 's', label: 'Pièces' }, pieces })
    .state;
  return reduceNav(gallery, { type: 'select', ref: select }).state;
}

describe('Jarvis — navigation d’une architecture (F-238 / SF-238-10)', () => {
  it('ouvrir une architecture pose son sous-état ; une autre pièce ou la galerie le retire', () => {
    const nav = opened();
    expect(nav.arch).toEqual(defaultArchNav());
    expect(reduceNav(nav, { type: 'next' }).state.arch).toBeNull();
    expect(reduceNav(nav, { type: 'back' }).state.arch).toBeNull();
  });

  it('replier puis déplier ⇒ état identique (ordre indifférent, sans doublon)', () => {
    const start = defaultArchNav();
    const b = { id: 'azb', label: 'Availability Zone B' };
    const a = { id: 'priva', label: 'Sous-réseau privé A' };
    const folded = reduceArch(reduceArch(reduceArch(start, { kind: 'fold', refs: [b] }), { kind: 'fold', refs: [a] }),
      { kind: 'fold', refs: [b] });
    expect(folded.folded.map((r) => r.id)).toEqual(['azb', 'priva']);
    const back = reduceArch(reduceArch(folded, { kind: 'unfold', refs: [a] }), { kind: 'unfold', refs: [b] });
    expect(back).toEqual(start);
    expect(reduceArch(folded, { kind: 'unfold', refs: 'all' })).toEqual(start);
  });

  it('les demandes sans graphe passent au réducteur ; les autres attendent la résolution', () => {
    expect(trivialArchOp({ kind: 'explode', on: true })).toEqual({ kind: 'explode', on: true });
    expect(trivialArchOp({ kind: 'view', view: '2d' })).toEqual({ kind: 'flat', on: true });
    expect(trivialArchOp({ kind: 'turn' })).toEqual({ kind: 'orbit', dAz: TURN_STEP, dEl: 0 });
    expect(trivialArchOp({ kind: 'focus', target: 'la base' })).toBeNull();
    const nav = opened();
    expect(reduceNav(nav, { type: 'arch', request: { kind: 'focus', target: 'la base' } }).reason).toBe('NOT_FOUND');
    const exploded = reduceNav(nav, { type: 'arch', request: { kind: 'explode', on: true } });
    expect(exploded.ok).toBeTrue();
    expect(exploded.state.arch?.exploded).toBeTrue();
  });

  it('une vue nommée remet l’orbite et le zoom à zéro ; l’orbite est bornée', () => {
    let nav = opened();
    nav = reduceNav(nav, { type: 'zoom', step: '+' }).state;
    nav = reduceNav(nav, { type: 'arch', request: { kind: 'orbit', dAz: 0.3, dEl: 9 } }).state;
    expect(nav.arch?.orbit.el).toBe(ORBIT_EL_MAX);
    nav = reduceNav(nav, { type: 'arch', request: { kind: 'view', view: 'dessus' } }).state;
    expect(nav.arch?.orbit).toEqual({ az: 0, el: 0 });
    expect(nav.zoom).toBe(1);
  });

  it('hors architecture : refus « pas possible sur cette pièce »', () => {
    const nav = opened([VIDEO, ARCH], 1);
    expect(reduceNav(nav, { type: 'arch', request: { kind: 'explode', on: true } }).reason).toBe('NOT_APPLICABLE');
  });

  it('grammaire locale : ordres exacts, sans IA ; « vue d’ensemble » est une vue du schéma ouvert', () => {
    expect(archVoiceRequest('Éclate !')).toEqual({ kind: 'explode', on: true });
    expect(archVoiceRequest('Jarvis, rassemble.')).toEqual({ kind: 'explode', on: false });
    expect(archVoiceRequest('Vue de dessus')).toEqual({ kind: 'view', view: 'dessus' });
    expect(archVoiceRequest('De face')).toEqual({ kind: 'view', view: 'face' });
    expect(archVoiceRequest('En 2D')).toEqual({ kind: 'view', view: '2d' });
    expect(archVoiceRequest('Replie tout')).toEqual({ kind: 'fold', target: '' });
    expect(archVoiceRequest('Déplie tout')).toEqual({ kind: 'unfold', target: '' });
    expect(archVoiceRequest('Tourne')).toEqual({ kind: 'turn' });
    // Ce qui nomme un service ou une zone part au cerveau rapide.
    expect(archVoiceRequest('Zoome sur la base')).toBeNull();
    expect(archVoiceRequest('Replie le VPC')).toBeNull();
    const nav = opened();
    expect(pieceVoiceCommand('Vue d’ensemble', nav)).toEqual({ type: 'arch', request: { kind: 'view', view: 'ensemble' } });
    expect(pieceVoiceCommand('Éclate', nav)).toEqual({ type: 'arch', request: { kind: 'explode', on: true } });
    const video = opened([VIDEO, ARCH], 1);
    expect(pieceVoiceCommand('Vue d’ensemble', video)).toEqual({ type: 'up' });
    expect(pieceVoiceCommand('Éclate', video)).toBeNull();
  });

  it('ligne d’état dans le bloc « ## Écran » (message), bornée à 1 500 caractères', () => {
    let nav = opened();
    nav = reduceNav(nav, { type: 'arch', request: { kind: 'focus', target: 'rdsa' },
      op: { kind: 'focus', ref: { id: 'rdsa', label: 'RDS primaire' } } }).state;
    nav = reduceNav(nav, { type: 'arch', request: { kind: 'fold', target: 'zone B' },
      op: { kind: 'fold', refs: [{ id: 'azb', label: 'Availability Zone B' }] } }).state;
    const block = screenBlock(nav);
    expect(block).toContain('Architecture « 3-tiers multi-AZ » ; focus : RDS primaire ; repliées : Availability '
      + 'Zone B ; éclaté : non ; vue : ensemble ; flux : aucun');
    const many: ArchNav = { ...defaultArchNav(), folded: Array.from({ length: 40 }, (_, i) => ({ id: `z${i}`,
      label: 'Une zone au libellé particulièrement long pour remplir la ligne' })) };
    const big = screenBlock({ ...nav, arch: many });
    expect(big.length).toBeLessThanOrEqual(SCREEN_MAX_CHARS);
    expect(archScreenLine('x', many)).toContain('+34');
  });

  it('outil `afficher` : les verbes d’architecture deviennent des demandes (cible = le nom dit)', () => {
    const ctx = { subjectId: 's', subjectName: null, presentation: false, sober: false, nav: opened(),
      recent: new Map<string, number>(), now: 0 };
    expect(showPlan({ action: 'focus', target: 'la base' }, ctx)).toEqual({ kind: 'nav',
      action: { type: 'arch', request: { kind: 'focus', target: 'la base' } }, origin: 'ASKED' });
    expect(showPlan({ action: 'flux', target: 'entrée', reason: 'appui' }, ctx)).toEqual({ kind: 'nav',
      action: { type: 'arch', request: { kind: 'flow', target: 'entrée' } }, origin: 'PROACTIVE' });
    expect(showPlan({ action: 'vue', view: 'dessus' }, ctx)).toEqual({ kind: 'nav',
      action: { type: 'arch', request: { kind: 'view', view: 'dessus' } }, origin: 'ASKED' });
    expect(showPlan({ action: 'vue', view: 'clients' }, ctx)).toEqual({ kind: 'refuse', reason: 'NOTHING' });
    expect(showPlan({ action: 'eclater' }, ctx).kind).toBe('nav');
    // Mode sobre : aucun mouvement de caméra d'initiative.
    expect(showPlan({ action: 'focus', target: 'ALB', reason: 'appui' }, { ...ctx, sober: true }))
      .toEqual({ kind: 'refuse', reason: 'NOTHING' });
  });
});
