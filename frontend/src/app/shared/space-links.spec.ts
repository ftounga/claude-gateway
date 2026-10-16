import { openClientRef, spaceLinks } from './space-links';

/** Les passerelles entre la Forge et la Vigie (F-106 / SF-106-04). */
describe('space-links', () => {
  it('lit le client ouvert dans la Forge et dans la Vigie', () => {
    expect(openClientRef('/forge/h1')).toBe('h1');
    expect(openClientRef('/forge/h1?onglet=carte')).toBe('h1');
    expect(openClientRef('/vigie/h1')).toBe('h1');
    expect(openClientRef('/vigie/h1/sujets/s1')).toBe('h1');
  });

  it("ignore ce qui n'est pas un client", () => {
    for (const url of ['/forge', '/forge/voir', '/forge/supervision', '/forge/heberge', '/chat',
      '/atelier/w1', '/forge#poste-h1', null]) {
      expect(openClientRef(url)).withContext(String(url)).toBeNull();
    }
  });

  it('porte le client vers les deux espaces', () => {
    expect(spaceLinks('/forge/h1')).toEqual({ forge: ['/forge', 'h1'], vigie: ['/vigie', 'h1'] });
    expect(spaceLinks('/chat')).toEqual({ forge: ['/forge'], vigie: ['/vigie'] });
  });

  it("à défaut de client dans l'adresse, porte le client courant (F-199 / SF-199-01)", () => {
    expect(spaceLinks('/atelier/w1', 'h2')).toEqual({ forge: ['/forge', 'h2'], vigie: ['/vigie', 'h2'] });
    expect(spaceLinks('/chat', 'h2')).toEqual({ forge: ['/forge', 'h2'], vigie: ['/vigie', 'h2'] });
  });

  it("le client de l'adresse prime sur le client courant", () => {
    expect(spaceLinks('/forge/h1', 'h2')).toEqual({ forge: ['/forge', 'h1'], vigie: ['/vigie', 'h1'] });
  });

  it("« Hébergé » n'est porté que vers la Forge", () => {
    expect(spaceLinks('/atelier/w1', 'heberge')).toEqual({ forge: ['/forge', 'heberge'], vigie: ['/vigie'] });
    expect(spaceLinks('/atelier/w1', '  ')).toEqual({ forge: ['/forge'], vigie: ['/vigie'] });
    expect(spaceLinks('/atelier/w1', null)).toEqual({ forge: ['/forge'], vigie: ['/vigie'] });
  });
});
