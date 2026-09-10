import {
  DEFAULT_MISSION_STATUS,
  MISSION_STATUSES,
  isMissionClosed,
  missionBadgeClass,
  missionHint,
  missionIcon,
  missionLabel,
  normalizeMissionStatus,
} from './mission-status';

/**
 * L'état de mission d'un poste (F-60 / SF-60-02).
 *
 * <p>Deux propriétés se prouvent ici, et ce sont celles qui tiennent la feature :</p>
 * <ul>
 *   <li>l'état ne porte <b>jamais</b> une couleur seule — chaque valeur a un libellé écrit ;</li>
 *   <li>l'état n'emprunte <b>aucune</b> couleur : il ne rend que des classes de la charte, ce qui
 *       lui interdit structurellement d'aller piocher dans la palette d'identité des postes.</li>
 * </ul>
 */
describe('mission-status', () => {

  // ------------------------------------------------------------------ normalisation

  it('lit une valeur absente, nulle ou vide comme une mission en cours', () => {
    // Jamais « inconnu » : un poste dont la gateway ne dit rien EST une mission en cours.
    expect(normalizeMissionStatus(undefined)).toBe('ACTIVE');
    expect(normalizeMissionStatus(null)).toBe('ACTIVE');
    expect(normalizeMissionStatus('')).toBe('ACTIVE');
    expect(DEFAULT_MISSION_STATUS).toBe('ACTIVE');
  });

  it('rend les trois valeurs légitimes telles quelles', () => {
    expect(normalizeMissionStatus('ACTIVE')).toBe('ACTIVE');
    expect(normalizeMissionStatus('PENDING')).toBe('PENDING');
    expect(normalizeMissionStatus('CLOSED')).toBe('CLOSED');
  });

  it('replie toute valeur inconnue plutôt que de la recopier à l’écran', () => {
    // La valeur vient d'une API : elle n'atteint jamais un gabarit sans passer par ici.
    expect(normalizeMissionStatus('DONE')).toBe('ACTIVE');
    expect(normalizeMissionStatus('closed')).toBe('ACTIVE');
    expect(normalizeMissionStatus('<script>')).toBe('ACTIVE');
  });

  // ------------------------------------------------------------------ libellés

  it('donne à chaque état un libellé écrit et non vide', () => {
    for (const status of MISSION_STATUSES) {
      expect(missionLabel(status).trim().length).toBeGreaterThan(0);
      expect(missionHint(status).trim().length).toBeGreaterThan(0);
      expect(missionIcon(status).trim().length).toBeGreaterThan(0);
    }
    expect(missionLabel('ACTIVE')).toBe('En cours');
    expect(missionLabel('PENDING')).toBe('En attente');
    expect(missionLabel('CLOSED')).toBe('Clôturé');
  });

  it('propose les trois états, du plus vivant au plus rangé', () => {
    expect([...MISSION_STATUSES]).toEqual(['ACTIVE', 'PENDING', 'CLOSED']);
  });

  // ------------------------------------------------------------------ couleurs

  it('emprunte les pastilles de STATUT de la charte, et rien d’autre', () => {
    expect(missionBadgeClass('ACTIVE')).toBe('badge--success');
    expect(missionBadgeClass('PENDING')).toBe('badge--warning');
    expect(missionBadgeClass('CLOSED')).toBe('badge--neutral');
  });

  it("n'écrit aucune couleur : l'état ne peut pas concurrencer l'identité du poste", () => {
    // Le piège du cadrage F-60. L'identité (DESIGN_SYSTEM §9) est faite de valeurs hexadécimales
    // dérivées du nom ; l'état, lui, ne rend QUE des noms de classe. Si une valeur hexadécimale
    // apparaissait ici, les deux registres commenceraient à se disputer la même surface.
    for (const status of MISSION_STATUSES) {
      expect(missionBadgeClass(status)).not.toMatch(/#|rgb|hsl/);
      expect(missionLabel(status)).not.toMatch(/#[0-9a-f]{6}/i);
    }
  });

  // ------------------------------------------------------------------ rangement

  it('ne range que les missions clôturées', () => {
    expect(isMissionClosed('CLOSED')).toBeTrue();
    expect(isMissionClosed('ACTIVE')).toBeFalse();
    expect(isMissionClosed('PENDING')).toBeFalse();
    // Un poste dont on ignore l'état reste au premier plan : on ne range jamais par défaut.
    expect(isMissionClosed(undefined)).toBeFalse();
    expect(isMissionClosed('inconnu')).toBeFalse();
  });
});
