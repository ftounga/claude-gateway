import { ATELIER_GUIDE_STEPS } from './atelier-guide.component';

/**
 * F-58 / SF-58-01 — garde-fou de vocabulaire sur le guide d'accueil (F-53).
 *
 * <p>Le cadrage de F-58 demandait explicitement de vérifier les textes du guide. Ils ne prononçaient
 * déjà pas le mot — ils parlent de « projet », « poste », « commande ». Ce test transforme ce
 * constat en garantie : si une étape venait à nommer l'écran, elle devrait le nommer
 * <b>Forge</b>.</p>
 *
 * <p>La constante conserve volontairement son nom interne (`ATELIER_GUIDE_STEPS`) : F-58 ne renomme
 * que ce que l'utilisateur lit, jamais le code.</p>
 */
describe('Guide d’accueil — vocabulaire visible (F-58)', () => {
  it('ne prononce jamais le mot « Atelier »', () => {
    for (const step of ATELIER_GUIDE_STEPS) {
      const visible = [step.title, step.text, step.action ?? ''].join(' ');

      expect(visible).not.toMatch(/atelier/i);
    }
  });
});
