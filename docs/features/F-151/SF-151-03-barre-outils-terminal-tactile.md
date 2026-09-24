# Mini-spec — F-151 / SF-151-03 — Barre d'outils terminal + boutons de décision au pouce

## Identifiant

`F-151 / SF-151-03`

## Feature parente

`F-151` — Atelier mobile (responsive du chemin critique)

## Statut

`ready`

## Date de création

2026-09-24

## Branche Git

`feat/SF-151-03-barre-outils-terminal-tactile`

---

## Objectif

Sous 819 px, faire passer à la ligne (plutôt que déborder) la barre d'en-tête du terminal et ses
actions, et dimensionner **au pouce** (≥ 44 px) les boutons de décision (**Interrompre**,
**Autoriser / Tout autoriser / Refuser**), sans jamais provoquer de défilement horizontal, et sans
régression au-dessus de 820 px.

---

## Comportement attendu

### Cas nominal

- **≥ 820 px** : rien ne change. La barre `.terminal-bar` et ses actions `.terminal-bar-actions`
  restent en ligne ; les boutons gardent leur taille compacte. Rendu **byte-identique**.
- **< 819 px** :
  - `.terminal-bar` et `.terminal-bar-actions` passent en `flex-wrap: wrap` : les nombreux boutons
    (Interrompre, Instructions, Valider les commandes, Fichiers, Publier, Réinitialiser, Nouveau
    départ, Quitter) s'empilent sur plusieurs lignes au lieu de déborder — **jamais de scroll-x**.
  - Les boutons de décision d'autorisation `.terminal-ask-actions button` (Autoriser / Tout
    autoriser pour ce message / Refuser / Confirmer le refus) passent à la ligne et reçoivent une
    hauteur tactile **≥ 44 px** ; ils restent atteignables sans défilement horizontal.
  - **Interrompre** (`.terminal-interrupt`) et les boutons de la zone de saisie
    (`.terminal-input button` : joindre, dictée, envoyer) reçoivent une cible tactile ≥ 44 px.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Beaucoup d'actions actives à la fois (run en cours + git + hébergé) | Les boutons s'empilent sur plusieurs lignes, aucun n'est coupé ni hors écran |
| Demande d'autorisation avec bandeau « administrateur » | Le bandeau (`.terminal-ask-elevated`, un `<p>`) prend sa ligne ; les trois boutons de décision restent tactiles et visibles |

---

## Critères d'acceptation

- [ ] À < 819 px, `.terminal-bar` et `.terminal-bar-actions` sont en `flex-wrap: wrap` ; aucun débordement horizontal à 400 px.
- [ ] À < 819 px, `.terminal-ask-actions` est `flex-wrap: wrap` et ses `button` font ≥ 44 px de haut.
- [ ] À < 819 px, `.terminal-interrupt` et `.terminal-input button` font ≥ 44 px de haut.
- [ ] À ≥ 820 px, aucun de ces styles ne s'applique (rendu desktop inchangé).
- [ ] Les nouveaux styles vivent dans une **feuille dédiée** (la feuille principale du terminal est au budget de build de 12 ko, angular.json / F-117) ; le build reste vert.
- [ ] Aucune couleur/police hors DESIGN_SYSTEM ; aucun changement de logique, d'endpoint, de protocole runner.

---

## Périmètre

### Hors scope (explicite)

- Le shell global (SF-151-01) et le shell Atelier (SF-151-02), déjà livrés.
- Toute refonte du contenu de la barre (pas de « … » à état ; on passe à la ligne, cf. cadrage qui
  autorise « repliée sous « … » **ou** passée à la ligne »).
- Le pilotage F-84, l'installabilité (F-152), les notifications (F-153).

---

## Contraintes de validation

Aucune donnée saisie. Point de rupture imposé : **819 px** (`max-width: 819px`). Cible tactile
**≥ 44 px** (charte responsive F-151, DESIGN_SYSTEM).

---

## Technique

### Endpoint(s) / Tables / Migration

Aucun. Migration : **non applicable**.

### Composants Angular

- `AtelierTerminalComponent` — ajout d'une **14ᵉ feuille de style**
  `atelier-terminal-mobile.component.scss` à `styleUrls` (unique modification `.ts`, purement
  déclarative) ; la feuille contient un bloc `@media (max-width: 819px)`. Aucun `.html` touché,
  aucune logique.

---

## Préoccupation transversale

Aucune préoccupation transversale déclenchée : pas d'auth, pas de tenant, pas de plan, pas de
route/guard (la barre d'outils ne navigue pas — ses boutons émettent des events déjà câblés,
inchangés).

---

## Plan de test

### Tests

- [ ] Non-régression : `atelier-terminal.component.spec.ts` reste **entièrement vert** (aucune
      logique ni HTML touché ; seule une feuille de style s'ajoute).
- [ ] `ng build` production **vert** ; la feuille principale reste sous le budget de 12 ko (les
      nouveaux styles sont dans une feuille dédiée) ; la feuille dédiée sous 4 ko.

> **Pourquoi pas de test unitaire de media-query** : comportement 100 % CSS dépendant de la largeur
> réelle du viewport, non pilotable de façon fiable en Karma/ChromeHeadless. Validité garantie par
> `ng build` + non-régression ; point de rupture 819 px éprouvé (patron `/forge`, SF-151-01/02).

### Isolation workspace / user_id

- [x] Non applicable — aucun accès données (pur affichage CSS).

---

## Dépendances

Aucune subfeature bloquante (indépendante). Aucune question ouverte impactée.

---

## Notes et décisions

- **D-14e-feuille** : nouvelle feuille dédiée plutôt qu'ajout à `atelier-terminal.component.scss`,
  qui est **au budget de build de 12 ko** (angular.json) — le franchir fait échouer la compilation.
  Même décision que les 13 feuilles précédentes (F-117, F-115, F-126, F-146, F-145, F-121, F-150).
- **D-wrap-pas-menu** : on **passe à la ligne** (flex-wrap) plutôt que de replier sous un « … ». Le
  cadrage autorise les deux ; le wrap évite de dupliquer les boutons et tout nouvel état, et ne
  touche pas le HTML. Cibles tactiles ≥ 44 px sur les boutons de décision (les plus critiques).
