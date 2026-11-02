# Mini-spec — F-240 / SF-240-03 — Ce qu'il montre : l'écran en trois étages

## Identifiant
`F-240 / SF-240-03` · Feature parente `F-240` « Jarvis va à l'essentiel » · Statut `in-review` · 2026-11-02 ·
Branche `feat/SF-240-03-trois-etages`

## Objectif
> Sous l'orbe, l'écran `/jarvis` montre la phrase dite, puis **l'essentiel seul**, et le **détail reste replié** (sans
> répéter l'essentiel) ; le toucher qui l'ouvre est mesuré.

## Comportement attendu
### Cas nominal
- Zone « Dernier échange » : (1) la phrase dite (`jv__spoken`, existante) ; (2) **l'essentiel** (`jv__essential`) = le
  bloc `<<essentiel>>` de la réponse découpé par **`splitEssential` (F-126, réutilisé tel quel, import direct)**,
  rendu Markdown ; (3) « Voir le détail » déplie **le détail seul** (`jv__detail`).
- Avant découpage, les lignes jamais montrées sont retirées (« 🎙 » déjà dite, « ↪ » invitation, « 🖥 montrer: »), hors
  blocs de code : la ligne dite, écrite avant le bloc, ne tombe plus dans le détail.
- **Repli sans bloc** : les 5 premières lignes de prose (jusqu'au premier tableau ou bloc de code) font l'étage 2, le
  reste reste replié ; une réponse qui commence par un tableau ou du code n'a pas d'essentiel.
- Pendant le flux, rien n'est montré (l'écran montre la réponse à sa fin, comme avant) : le marqueur brut n'est jamais
  visible.
- **Mêmes étages** pour une fin de délégation (F-235). Le mode présentation et le cockpit sont inchangés.
- Une nouvelle réponse **replie** le détail ; sans détail, pas de bouton.
- **Toucher** (D10) : ouvrir « Voir le détail » pose `depth_reached = 3`, `depth_via = toucher` sur la mesure du tour
  dont la réponse est montrée (mesure gardée, SF-240-01).
- DESIGN_SYSTEM §22 : l'essentiel = texte crème sur `--cg-navy-2` à 70 %, Inter ; aucune couleur nouvelle ; téléphone :
  visible sans défilement de page à 360 × 740 (hauteur bornée, défilement interne), sans débordement horizontal.

### Cas d'erreur
| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Réponse vide ou seulement une ligne dite | Ni essentiel ni bouton | — |
| Bloc essentiel ouvert sans fermeture | Tout le reste est l'essentiel (règle `splitEssential`) | — |
| Réponse qui commence par un tableau / du code | Pas d'essentiel ; tout au détail | — |
| Ligne 🎙 citée dans un bloc de code | Reste dans le code | — |
| Mot ou lien très long à 360 px | Coupé, aucun débordement horizontal | — |

## Critères d'acceptation
- [ ] Une réponse avec bloc montre l'essentiel seul et le détail replié.
- [ ] Une réponse sans bloc suit le repli (5 lignes de prose).
- [ ] Le détail déplié ne contient ni l'essentiel ni la ligne dite.
- [ ] Au toucher, `depth_reached = 3` et `depth_via = toucher`.
- [ ] Aucune couleur hors du design system, pas de débordement horizontal à 360 px.

## Périmètre — hors scope
La voix (« dis-m'en plus » : SF-240-04) ; le terminal (inchangé : `splitEssential` n'y bouge pas).

## Contraintes de validation
| Champ | Obligatoire | Valeurs | Normalisation |
|---|---|---|---|
| Essentiel de repli | — | ≤ 5 lignes de prose | arrêt au 1er tableau / bloc de code |

## Technique
- Aucun endpoint, aucune table.
- Écran : `jarvis-tiers.ts` (nouveau : `jarvisTiers`, `stripVoiceLines`), `JarvisComponent` (`tiers` calculé,
  `showAnswer`, `toggleDetail`, `answerClock`), gabarit et styles (`jv__essential`), `DESIGN_SYSTEM.md` §22.

## Plan de test
- Unitaires : `jarvis-tiers.spec.ts` (avec / sans bloc, bloc seul, tableau et code, vide, 🎙 dans un bloc de code).
- Composant : essentiel affiché, détail replié puis déplié sans l'essentiel, toucher mesuré (`depthReached = 3`,
  `toucher`) ; nouvelle réponse qui replie ; pas de bouton sans détail ; 360 px sans débordement (`scrollWidth`).
- Isolation : sans objet (aucune donnée nouvelle, la réponse est celle du tour de l'utilisateur).

## Dépendances
SF-240-01 (mesure gardée), SF-240-02 (lignes « ↪ ») mergées. Aucune question ouverte.

## Notes et décisions
- **Contrôle visuel** : fait au banc de tests (navigateur headless de Karma, largeur 360 px, `scrollWidth` mesuré) plutôt
  qu'au banc headless sur l'application déployée (aucun déploiement par feature) ; à revoir à l'essai après déploiement.
- `splitEssential` importé depuis `atelier/terminal/essential.ts` (pas de déplacement vers `shared/` : l'import croisé
  ne gêne pas, aucun cycle).
