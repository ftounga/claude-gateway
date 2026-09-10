import { TestBed } from '@angular/core/testing';

import { AtelierGuideService } from './atelier-guide.service';

const STORAGE_KEY = 'cg_atelier_guide';

/**
 * Mémoire locale du guide d'accueil (F-53 / SF-53-01).
 *
 * <p>Le service est instancié à chaque `TestBed.inject` : c'est ce qui permet de vérifier la
 * <b>relecture</b> d'un état déjà écrit, exactement comme au rechargement de la page.</p>
 */
describe('AtelierGuideService', () => {
  function fresh(): AtelierGuideService {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({});
    return TestBed.inject(AtelierGuideService);
  }

  beforeEach(() => localStorage.removeItem(STORAGE_KEY));
  afterEach(() => localStorage.removeItem(STORAGE_KEY));

  it('démarre neuf quand rien n\'est mémorisé', () => {
    const guide = fresh();

    expect(guide.status()).toBe('active');
    expect(guide.steps()).toEqual({ project: false, host: false, command: false });
    expect(guide.visible()).toBeTrue();
    expect(guide.completed()).toBeFalse();
  });

  it('coche une étape, la persiste, et la relit au rechargement', () => {
    fresh().markStep('host');

    const reloaded = fresh();
    expect(reloaded.steps().host).toBeTrue();
    expect(reloaded.steps().project).toBeFalse();
    expect(reloaded.visible()).toBeTrue();
  });

  it('ne décoche jamais une étape déjà franchie', () => {
    const guide = fresh();
    guide.markStep('project');
    guide.markStep('project');

    expect(guide.steps().project).toBeTrue();
  });

  it('après un abandon, ne réapparaît plus, même après rechargement', () => {
    const guide = fresh();
    guide.dismiss();

    expect(guide.visible()).toBeFalse();
    expect(fresh().visible()).toBeFalse();
    expect(fresh().status()).toBe('dismissed');
  });

  it('affiche sa conclusion quand la troisième étape tombe, puis se termine', () => {
    const guide = fresh();
    guide.markStep('project');
    guide.markStep('host');
    expect(guide.completed()).toBeFalse();

    guide.markStep('command');
    // Le parcours vient de s'accomplir ICI : la conclusion doit rester à l'écran.
    expect(guide.completed()).toBeTrue();
    expect(guide.visible()).toBeTrue();

    guide.finish();
    expect(guide.visible()).toBeFalse();
    expect(fresh().status()).toBe('done');
  });

  it('ne s\'ouvre pas pour qui a déjà tout accompli lors d\'une session précédente', () => {
    const guide = fresh();
    guide.markStep('project');
    guide.markStep('host');
    guide.markStep('command');

    const reloaded = fresh();
    expect(reloaded.status()).toBe('active');
    expect(reloaded.completed()).toBeTrue();
    expect(reloaded.visible()).toBeFalse();
  });

  it('repart neuf sur un contenu illisible', () => {
    localStorage.setItem(STORAGE_KEY, '{ pas du json');

    const guide = fresh();
    expect(guide.status()).toBe('active');
    expect(guide.steps().project).toBeFalse();
  });

  it('repart neuf sur une version inconnue ou un statut inattendu', () => {
    localStorage.setItem(
      STORAGE_KEY,
      JSON.stringify({ version: 99, status: 'dismissed', steps: { project: true } }),
    );
    expect(fresh().steps().project).toBeFalse();

    localStorage.setItem(
      STORAGE_KEY,
      JSON.stringify({ version: 1, status: 'n\'importe quoi', steps: { project: 'oui' } }),
    );
    const guide = fresh();
    expect(guide.status()).toBe('active');
    expect(guide.steps().project).toBeFalse();
  });

  it('ne lève jamais quand le stockage local est refusé', () => {
    spyOn(Storage.prototype, 'getItem').and.throwError('storage refusé');
    spyOn(Storage.prototype, 'setItem').and.throwError('storage refusé');

    const guide = fresh();
    expect(guide.status()).toBe('active');
    expect(() => guide.markStep('command')).not.toThrow();
    expect(() => guide.dismiss()).not.toThrow();
    // L'avancement vaut au moins pour la session en cours.
    expect(guide.steps().command).toBeTrue();
  });

  // --- Reprise et échec de tour (F-53 / SF-53-02) ---

  it('se rouvre après un abandon, étapes conservées, et la reprise est mémorisée', () => {
    const guide = fresh();
    guide.markStep('project');
    guide.dismiss();

    guide.reopen();
    expect(guide.visible()).toBeTrue();
    expect(guide.steps().project).toBeTrue();

    const reloaded = fresh();
    expect(reloaded.status()).toBe('active');
    expect(reloaded.visible()).toBeTrue();
  });

  it('rouvre un parcours accompli sur sa conclusion', () => {
    const guide = fresh();
    guide.markStep('project');
    guide.markStep('host');
    guide.markStep('command');
    guide.finish();
    expect(guide.visible()).toBeFalse();

    guide.reopen();
    expect(guide.visible()).toBeTrue();
    expect(guide.completed()).toBeTrue();

    guide.finish();
    expect(guide.visible()).toBeFalse();
  });

  it('signale un tour en échec, et l\'oublie dès qu\'un tour aboutit', () => {
    const guide = fresh();
    expect(guide.turnFailed()).toBeFalse();

    guide.markTurnFailed();
    expect(guide.turnFailed()).toBeTrue();
    // L'étape ne se coche pas pour autant : un tour en échec n'est pas le premier succès.
    expect(guide.steps().command).toBeFalse();

    guide.markStep('command');
    expect(guide.turnFailed()).toBeFalse();
    expect(guide.steps().command).toBeTrue();
  });

  it('n\'écrit jamais l\'échec dans le stockage local', () => {
    const guide = fresh();
    guide.markStep('project');
    guide.markTurnFailed();

    expect(localStorage.getItem(STORAGE_KEY)).not.toContain('ailed');
    // Rechargée, la session repart sans échec : c'est un état d'un instant.
    expect(fresh().turnFailed()).toBeFalse();
  });
});
