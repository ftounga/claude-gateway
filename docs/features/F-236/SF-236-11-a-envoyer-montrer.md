# Mini-spec — [F-236 / SF-236-11] À envoyer et Montrer (S1 + T-P3, L-message, S20 × 6 filtres + T-M)

## Identifiant
SF-236-11

## Feature parente
F-236 — Jarvis en 3D (cadrage §9.2 S1/S20, §7.4 T-P3/T-M, §8.2 L-message ; famille 6 de l'usage F-234)

## Statut
done

## Date de création
2026-11-02

## Branche Git
`feat/SF-236-11-a-envoyer-montrer`

---

## Objectif

Montrer en 3D le message qui se prépare puis file dans « À envoyer », et traduire exactement « Montrer » (6 filtres)
dans l'univers.

## Comportement attendu

### Cas nominal

1. **S1 Enveloppe / bulle** : la caméra cadre le **destinataire** (sinon le poste) ; l'enveloppe holographique
   (teinte d'attente) écrit **« À : … » puis « Objet : … »** — **jamais le corps** (le lecteur F-234 ne le lit
   pas) ; « Preuves jointes » si des captures sont jointes ; un courriel pour soi s'écrit « Pour vous » en teinte
   normale. Son filament d'ancrage, de la personne à l'enveloppe, est la liaison **L-message**.
2. **T-P3** : à la fin du bloc, l'enveloppe **vole** vers le compteur « À envoyer » du HUD (1,1 s, `glisse`, arc,
   réduction 1 → 0,25, traînée) ; le compteur ne prend **+1 qu'à son arrivée** et **gonfle** 1 → 1,12 → 1 (`rebond`,
   0,5 s). En 2D, le compteur change immédiatement (inchangé). Un courriel pour soi ne vole pas.
3. **S20 Montrer** : la demande déjà résolue par F-234 (`resolveShow`, filtres fermés, ≤ 8) est traduite **à
   l'identique** : mêmes éléments, même ordre. `connectes` en **vert**, `deconnectes` **éteints**,
   `chantiers-bloques` en teinte d'**attente** (+ personne attendue), `etapes-en-cours` : chantiers déployés (chapelet)
   + l'étape en panneau, `a-envoyer` : une enveloppe par destinataire, `clients` : tout déployé ; titre (« 1 poste
   connecté sur 2 », « +n autres ») au cœur ; le reste en latent.
4. **T-M** : la caméra dézoome (vue d'ensemble), puis les éléments s'allument en cascade (**50 ms d'écart**) ; à la fin
   de « Montrer », leurs teintes d'origine reviennent.
5. S1 quitte le repli S-GEN.

### Cas d'erreur

| Cas | Comportement |
|---|---|
| Filtre inconnu (`🖥 montrer: licornes`) | `resolveShow` rend `null` : **rien** n'est montré. |
| Destinataire non reconnu | Enveloppe ancrée au poste (sinon au cœur). |
| Compteur absent du HUD au départ du vol | L'enveloppe vole vers le coin haut droit. |
| Plus d'éléments que le pool | Titre d'abord, puis les premiers éléments (PC 7, téléphone : titre seul). |
| Le compteur baisse (relecture de l'univers) | Mis à jour immédiatement, sans animation. |

---

## Critères d'acceptation

- [x] CA1 — `toSendAdded` +1 ⇒ compteur +1 **à l'arrivée** en 3D (délai T-P3), immédiat en 2D (tests `arrivalDelay`,
  `flyLook`).
- [x] CA2 — **Jamais de corps** : seules les lignes destinataire / objet / preuves (tests).
- [x] CA3 — Chaque filtre = **exactement** `resolveShow` (6 filtres, tests) ; filtre inconnu ⇒ rien (test) ; aucune
  teinte d'alerte dans « Montrer » (test).
- [x] CA4 — Captures × 6 filtres (téléphone + PC) + S1 + séquence T-P3 (6 images), non vides, sans erreur console.

## Contraintes de validation

| Élément | Règle |
|---|---|
| Lignes d'enveloppe | 80 c. (`safeLine`), destinataire et objet seulement. |
| T-P3 | 1,1 s ; compteur +1 à l'arrivée ; gonflement 0,5 s. |
| T-M | 50 ms d'écart entre éléments. |

## Plan de test

- Unitaires : `scenes3d/jarvis-show-3d.spec.ts` (S1, T-P3, 6 filtres, filtre inconnu, teintes, panneaux, univers).
- Composant : `jarvis.component.spec.ts` vert (compteur en 2D inchangé).
- Isolation utilisateur : sans objet (aucune lecture nouvelle, aucun appel).
- Visuel : `scene-message`, `montrer-*` × 6, séquence `scene-envoi` (0 → 1 100 ms).

## Périmètre

### Impacté
`scenes3d/jarvis-scene-message.ts`, `scenes3d/jarvis-show-3d.ts` (nouveaux), `scenes3d/index.ts`, modèle (`fly`),
fixture ; `jarvis-panels.ts` (`flyLook`, `arrivalDelay`) ; `engine/jarvis-panels-3d.ts` (vol), `engine/jarvis-universe-3d.ts`
(teintes et cascade de « Montrer »), `engine/jarvis-engine.ts` ; `jarvis-stage-director.ts`, `jarvis-stage-3d.component.ts` ;
`jarvis.component.*` (compteur qui attend l'arrivée, gonflement) ; banc.

### Hors scope (explicite)
- L'étagère « À envoyer » détachable (SF-236-18) ; aucun envoi de message (Jarvis ne fait que montrer).
- Aucun endpoint, aucune table, aucun appel IA, aucune consigne touchée.

## Préoccupations transversales
Aucune cochée (le compteur du HUD est le même ; seule son animation change en 3D).

## Notes et décisions

| # | Arbitrage | Pourquoi | Alternative écartée | Réversible |
|---|---|---|---|---|
| A1 | Le nombre affiché du compteur attend 1,1 s en 3D ; le compteur lui-même apparaît dès la fin du bloc. | La cible du vol doit exister au départ ; « +1 à l'arrivée » (CA). | Masquer le compteur pendant le vol. | Oui. |
| A2 | L-message = le filament d'ancrage de l'enveloppe (personne ↔ enveloppe), puis la traînée du vol (enveloppe ↔ compteur). | Une seule liaison lisible, sans objet 3D de plus. | Un filament 3D séparé. | Oui. |
| A3 | « Montrer » : panneaux des éléments seulement pour `etapes-en-cours` et `a-envoyer`. | Les autres filtres se lisent par la teinte des nœuds et leurs étiquettes. | Un panneau par élément partout. | Oui. |
| A4 | `clients` : postes déconnectés éteints. | La couleur ne porte jamais seule l'information, l'étiquette reste ; cohérent avec `deconnectes`. | Tout en teinte normale. | Oui. |
