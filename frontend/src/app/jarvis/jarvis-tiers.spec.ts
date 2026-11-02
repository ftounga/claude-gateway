import { jarvisTiers, stripVoiceLines } from './jarvis-tiers';

/** F-240 / SF-240-03 — la réponse en trois étages : l'essentiel seul, le détail sans l'essentiel. */
describe('jarvisTiers (F-240 / SF-240-03)', () => {
  it('avec bloc : l’essentiel seul ; le détail ne le répète pas ; la ligne dite n’y tombe pas', () => {
    const reply = '🎙 Le déploiement est terminé, Monsieur.\n↪ Il y a aussi un point sur Helios, je vous le dis ?\n'
      + '🖥 montrer: clients\n<<essentiel>>\n- Déployé à 10:42\n- Santé au vert\n- Certificat Helios : 05/11\n'
      + '<</essentiel>>\n\n## Détail\nTrois services mis à jour.';
    const tiers = jarvisTiers(reply);
    expect(tiers.essential).toBe('- Déployé à 10:42\n- Santé au vert\n- Certificat Helios : 05/11');
    expect(tiers.detail).toBe('## Détail\nTrois services mis à jour.');
    expect(tiers.detail).not.toContain('Déployé à 10:42');
    expect(tiers.detail).not.toContain('Monsieur');
    expect(tiers.detail).not.toContain('Helios, je vous');
    expect(tiers.detail).not.toContain('montrer');
  });

  it('bloc seul : pas de détail à déplier', () => {
    expect(jarvisTiers('🎙 Oui.\n<<essentiel>>\nOui, c’est fait.\n<</essentiel>>')).toEqual({
      essential: 'Oui, c’est fait.', detail: null,
    });
  });

  it('sans bloc : les 5 premières lignes de prose font l’essentiel, le reste reste replié', () => {
    const reply = '🎙 Voici.\nUn.\nDeux.\n\nTrois.\nQuatre.\nCinq.\nSix.\nSept.';
    const tiers = jarvisTiers(reply);
    expect(tiers.essential).toBe('Un.\nDeux.\n\nTrois.\nQuatre.\nCinq.');
    expect(tiers.detail).toBe('Six.\nSept.');
  });

  it('sans bloc : l’essentiel s’arrête au premier tableau ou bloc de code', () => {
    const tiers = jarvisTiers('Le coût de recette a monté.\n| env | € |\n|---|---|\n| dev | 412 |');
    expect(tiers.essential).toBe('Le coût de recette a monté.');
    expect(tiers.detail).toContain('| dev | 412 |');
    expect(jarvisTiers('```bash\nmvn test\n```\nFin.').essential).toBeNull();
  });

  it('réponse vide ou seulement dite : rien à montrer', () => {
    expect(jarvisTiers(null)).toEqual({ essential: null, detail: null });
    expect(jarvisTiers('🎙 C’est fait.')).toEqual({ essential: null, detail: null });
  });

  it('une ligne 🎙 citée dans un bloc de code reste dans le code', () => {
    expect(stripVoiceLines('Texte.\n```\n🎙 exemple\n```')).toContain('🎙 exemple');
  });
});
