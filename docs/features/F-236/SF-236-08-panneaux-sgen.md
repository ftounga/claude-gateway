# Mini-spec — [F-236 / SF-236-08] Panneaux holographiques, S-GEN 3D, registre `SCENES_3D` et sa garde

## Identifiant
SF-236-08

## Feature parente
F-236 — Jarvis en 3D (cadrage §3.2, §7.2, §7.4 T-P1/T-P2, §9.2 S-GEN, T6 ; itération 2 « Jarvis montre ce qu'il fait »)

## Statut
done

## Date de création
2026-11-02

## Branche Git
`feat/SF-236-08-panneaux-sgen`

---

## Objectif

Poser le **socle des scènes 3D** : des panneaux holographiques (DOM en verre, face à l'écran) ancrés aux nœuds, la
scène générique S-GEN en 3D, et le registre `SCENES_3D` dont la garde garantit que toute scène F-234 a un rendu 3D.

## Comportement attendu

### Cas nominal

1. **Contrat des scènes 3D** (`stage3d/scenes3d/`, pur, sans `three`) : une scène 3D est une fonction
   `(SceneView, SceneContext) → ScenePlan3d` = panneaux (`Panel3d` : ancre, teinte §23, titre, lignes, mono,
   avancement, « devant tout ») + effets déclaratifs (`Effect3d`) + nœud mis en avant. Le directeur l'ajoute au
   `StagePlan` (`plan.scene`) à partir de la scène F-234 en cours (`ReaderState.scene`, lue, jamais modifiée).
2. **Registre `SCENES_3D` + garde** : chaque scène connue (registre F-234 `scene`/`cible` + scènes posées par le
   lecteur) est soit dans `SCENES_3D`, soit déclarée dans `SGEN_3D_IDS` (repli volontaire sur S-GEN). Une scène
   nouvelle non déclarée fait échouer `jarvis-scenes3d.spec.ts`. Ligne ajoutée à `review-checklist.md`.
3. **S-GEN 3D** : un anneau d'impulsion part du cœur à chaque appel d'outil (`pulses`, 900 ms, `sortie`) ; le libellé du
   registre (« Radar · sujets du client × 3 ») + l'entité + le détail (déjà masqué, ≤ 80 c.) dans un petit panneau ;
   l'entité est allumée (D2 : ≤ 3), sinon le sujet courant. Une autorisation épinglée prend la teinte d'attente et passe
   devant tout.
4. **Panneaux** (`engine/jarvis-panels-3d.ts`) : verre holographique §7.2 (dégradé de la teinte 13 % → 0 sur navy
   72 %, flou 10 px sur PC, liseré, coins chanfreinés, trame qui défile 8 s, reflet 12 s) ; pool borné **PC 8 /
   téléphone 2** (« devant tout » jamais écarté) ; placement pur (`jarvis-panels.ts`) dans le **quadrant libre** le
   plus proche de l'ancre, **jamais sur le cœur, ni sur la barre du haut, ni sur la bande des sous-titres**, jamais sur
   un autre panneau, toujours dans l'écran, positions entières (texte net) ; sous un nœud, l'étiquette reste lisible.
5. **T-P1 / T-P2** animés par le moteur sur **son** horloge (captures reproductibles) : apparition 0,42 s (`rebond`,
   balayage 300 ms, filament d'ancrage 250 ms, 90 ms d'écart) ; disparition 0,28 s (`entree`, balayage inverse,
   filament rétracté). Un panneau qui revient repart de l'état courant.
6. En 3D, la carte 2D de la scène (décorative, `aria-hidden`) s'efface : la scène s'écrit dans les panneaux.

### Cas d'erreur

| Cas | Comportement |
|---|---|
| Scène sans rendu dédié, ou outil futur | S-GEN 3D (jamais rien). |
| Ancre absente de l'univers posé, ou derrière la caméra | Le panneau se pose autour du cœur. |
| Plus de panneaux que le pool | Les derniers ne sont pas dessinés ; « devant tout » d'abord. |
| Aucune place libre | Panneau ramené dans l'écran, hors bandes réservées (« clamp »). |
| Mode sobre / repli 2D | Aucun plan de scène 3D ; la scène 2D F-234 reste. |

---

## Critères d'acceptation

- [x] CA1 — Garde : chaque `scene.id` connu a un rendu 3D ou retombe déclarativement sur S-GEN (test) ; une scène
  nouvelle non déclarée est détectée (test).
- [x] CA2 — ≤ 8 panneaux sur PC, ≤ 2 sur téléphone ; « devant tout » jamais écarté (test).
- [x] CA3 — Placement : jamais sur le cœur, les sous-titres ou la barre du haut ; dans l'écran ; pas de recouvrement
  quand la place existe ; positions entières (tests).
- [x] CA4 — Séquences T-P1 et T-P2 (6 images chacune, téléphone + PC) ; règle « aucun saut » vérifiée (test du pas).
- [x] CA5 — S-GEN : libellé, « × n », impulsion par appel, entité allumée, teinte d'attente si épinglée (tests) ;
  captures non vides, sans erreur console.
- [x] CA6 — Bundle initial inchangé (485,20 ko) ; décor des panneaux posé par le morceau 3D paresseux.

## Contraintes de validation

| Élément | Règle |
|---|---|
| Panneaux | PC 8, téléphone 2 ; largeur ≤ 300 px (PC), ≤ 240 px (téléphone) ; marge 16 px. |
| Texte | Écrit par `textContent` (jamais de HTML injecté) ; déjà masqué et borné à 80 c. par le lecteur F-234. |
| Couleurs | Jetons §23 seulement (`--cg-holo-*`) ; polices de la charte. |

## Plan de test

- Unitaires : `scenes3d/jarvis-scenes3d.spec.ts` (garde, S-GEN, directeur) ; `jarvis-panels.spec.ts` (pool, placement,
  T-P1/T-P2, pas maximal).
- Composant : suites `jarvis.component.spec.ts` et `jarvis-stage-3d.component.spec.ts` inchangées et vertes.
- Isolation utilisateur : sans objet (aucune lecture nouvelle, aucun appel réseau).
- Visuel : `scene-sgen` (t = 300, 1200), séquences `panneau-entree` et `panneau-sortie`, téléphone + PC.

## Périmètre

### Impacté
`stage3d/scenes3d/*` (nouveau), `jarvis-panels.ts`, `engine/jarvis-panels-3d.ts`, `engine/jarvis-panels-style.ts`,
`engine/jarvis-effects-3d.ts`, `engine/jarvis-engine.ts`, `engine/jarvis-universe-3d.ts` (`locate`),
`jarvis-stage-director.ts`, `jarvis-stage-3d.component.ts`, `jarvis-motion.ts` (durées), banc, `jarvis.component.*`,
`review-checklist.md`.

### Hors scope (explicite)
- Les scènes dédiées (SF-236-09 → 13) : elles quittent `SGEN_3D_IDS` une à une.
- Aucun endpoint, aucune table, aucun appel IA, aucune consigne touchée.

## Préoccupations transversales
Aucune cochée (aucune route, aucun garde, aucune lecture nouvelle ; le préfixe n'est pas touché).

## Notes et décisions

| # | Arbitrage | Pourquoi | Alternative écartée | Réversible |
|---|---|---|---|---|
| A1 | Le panneau S-GEN s'ancre à l'**entité cadrée** quand elle existe, au cœur sinon. | La caméra cadre l'entité (§9.3) : ancré au cœur hors champ, le panneau partait au bord (vu au banc téléphone). | Toujours au cœur. | Oui. |
| A2 | Décor des panneaux en CSS posé par le **morceau 3D** (`<style>` unique), pas dans la feuille de l'écran. | La feuille de `/jarvis` frôlait le plafond d'erreur (12 ko) ; bundle initial +0. | SCSS de l'écran ; feuille globale. | Oui. |
| A3 | Panneaux animés en JS sur l'horloge du moteur, pas en CSS. | Captures reproductibles au banc (horloge figée) ; « le moteur interpole ». | Animations CSS. | Oui. |
| A4 | Le placement (DOM) n'est calculé que lorsqu'un panneau est affiché. | La règle « zéro allocation par image » reste vraie au repos ; un panneau DOM écrit ses styles. | Placement à chaque image. | Oui. |
| A5 | La carte 2D de la scène est masquée en 3D (elle reste en 2D et en mode sobre). | Pas de scène en double ; elle est `aria-hidden` (aucune perte d'accessibilité). | Laisser les deux. | Oui. |
