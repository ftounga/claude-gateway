import {
  HOST_IDENTITY_PALETTE,
  hostIdentity,
  hostInitials,
  hostTone,
  hostToneIndex,
} from './host-identity';

/**
 * L'identité visuelle d'un poste (F-49 / SF-49-03) : ce qui la rend **stable**, ce qui la rend
 * **lisible**, et ce qu'elle fait des noms qui ne ressemblent à rien.
 */
describe('host-identity', () => {

  // ------------------------------------------------------------------ stabilité

  it('rend le même ton pour le même nom (fonction pure)', () => {
    expect(hostTone('Client Alpha')).toEqual(hostTone('Client Alpha'));
    expect(hostToneIndex('Client Alpha')).toBe(hostToneIndex('Client Alpha'));
  });

  it('ignore la casse et les espaces de bord — le même poste garde la même couleur', () => {
    const reference = hostToneIndex('Client Alpha');
    expect(hostToneIndex('client alpha')).toBe(reference);
    expect(hostToneIndex('  Client   Alpha  ')).toBe(reference);
    expect(hostToneIndex('CLIENT ALPHA')).toBe(reference);
  });

  it('n\'écrit rien : deux appels séparés par un vidage de stockage donnent le même ton', () => {
    const before = hostTone('Poste CAGIP');
    localStorage.clear();
    sessionStorage.clear();
    expect(hostTone('Poste CAGIP')).toEqual(before);
  });

  it('reste dans les bornes de la palette, quel que soit le nom', () => {
    for (let i = 0; i < 500; i += 1) {
      const name = `poste-${i}-${(i * 7919).toString(36)}`;
      const index = hostToneIndex(name);
      expect(index).toBeGreaterThanOrEqual(0);
      expect(index).toBeLessThan(HOST_IDENTITY_PALETTE.length);
      expect(Number.isInteger(index)).toBeTrue();
    }
  });

  it('couvre toute la palette sur un échantillon de noms plausibles', () => {
    const drawn = new Set<number>();
    for (let i = 0; i < 300; i += 1) {
      drawn.add(hostToneIndex(`client ${i}`));
    }
    expect(drawn.size).toBe(HOST_IDENTITY_PALETTE.length);
  });

  it('distingue deux noms voisins', () => {
    // Non garanti par construction (10 tons), mais vérifié sur des noms réellement voisins.
    expect(hostToneIndex('Poste bureau')).not.toBe(hostToneIndex('Poste maison'));
  });

  it('ne lève jamais sur un nom absent, vide ou blanc', () => {
    for (const name of [null, undefined, '', '   ']) {
      expect(() => hostTone(name)).not.toThrow();
      expect(HOST_IDENTITY_PALETTE).toContain(hostTone(name));
      expect(hostToneIndex(name)).toBe(0);
    }
  });

  // ------------------------------------------------------------------ initiales

  it('tire les initiales des deux premiers mots', () => {
    expect(hostInitials('Poste CAGIP')).toBe('PC');
    expect(hostInitials('macbook-air')).toBe('MA');
    expect(hostInitials('poste_de_travail')).toBe('PD');
    expect(hostInitials('dev/perso')).toBe('DP');
    expect(hostInitials('Client Alpha Bêta')).toBe('CA');
  });

  it('prend les deux premières lettres d\'un mot unique', () => {
    expect(hostInitials('web')).toBe('WE');
    expect(hostInitials('é')).toBe('É');
  });

  it('rend « ? » quand le nom ne porte aucune lettre exploitable', () => {
    expect(hostInitials('')).toBe('?');
    expect(hostInitials('   ')).toBe('?');
    expect(hostInitials(null)).toBe('?');
    expect(hostInitials('— — —')).toBe('?');
  });

  it('rend l\'identité complète en un appel', () => {
    const identity = hostIdentity('  Poste CAGIP  ');
    expect(identity.name).toBe('Poste CAGIP');
    expect(identity.initials).toBe('PC');
    expect(identity.tone).toEqual(hostTone('Poste CAGIP'));
  });

  // ------------------------------------------------------------------ accessibilité

  /**
   * Contraste WCAG 2.1 — recalculé ici plutôt que recopié : c'est la seule façon qu'un ton ajouté
   * un jour à la palette ne puisse pas entrer sans passer le seuil.
   */
  function relativeLuminance(hex: string): number {
    const channel = (value: number): number => {
      const c = value / 255;
      return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    };
    const raw = hex.replace('#', '');
    const r = parseInt(raw.slice(0, 2), 16);
    const g = parseInt(raw.slice(2, 4), 16);
    const b = parseInt(raw.slice(4, 6), 16);
    return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b);
  }

  function contrast(a: string, b: string): number {
    const first = relativeLuminance(a);
    const second = relativeLuminance(b);
    const lighter = Math.max(first, second);
    const darker = Math.min(first, second);
    return (lighter + 0.05) / (darker + 0.05);
  }

  const WHITE = '#FFFFFF';
  /** Seuil AA pour du texte normal. Non négociable : la charte ne connaît pas d'exception. */
  const AA = 4.5;

  it('vérifie sa propre mesure de contraste sur les extrêmes connus', () => {
    expect(contrast('#000000', WHITE)).toBeCloseTo(21, 1);
    expect(contrast(WHITE, WHITE)).toBeCloseTo(1, 5);
  });

  it('garantit le contraste AA sur CHACUN des tons de la palette', () => {
    HOST_IDENTITY_PALETTE.forEach((tone, index) => {
      expect(contrast(WHITE, tone.solid))
        .withContext(`ton ${index} — blanc sur l'aplat`).toBeGreaterThanOrEqual(AA);
      expect(contrast(tone.ink, WHITE))
        .withContext(`ton ${index} — encre sur la carte blanche`).toBeGreaterThanOrEqual(AA);
      expect(contrast(tone.ink, tone.tint))
        .withContext(`ton ${index} — encre sur la teinte pâle`).toBeGreaterThanOrEqual(AA);
    });
  });

  it('n\'expose que des couleurs hexadécimales complètes, et dix tons distincts', () => {
    const hex = /^#[0-9A-F]{6}$/;
    expect(HOST_IDENTITY_PALETTE.length).toBe(10);
    for (const tone of HOST_IDENTITY_PALETTE) {
      expect(tone.solid).toMatch(hex);
      expect(tone.ink).toMatch(hex);
      expect(tone.tint).toMatch(hex);
    }
    expect(new Set(HOST_IDENTITY_PALETTE.map((tone) => tone.solid)).size).toBe(10);
  });
});
