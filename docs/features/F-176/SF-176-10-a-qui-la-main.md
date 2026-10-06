# Mini-spec — F-176 / SF-176-10 — À qui la main

## Identifiant
`F-176 / SF-176-10` — rouverture du 2026-10-06 (D7), validée PO. Dépend de SF-176-09 (#1087).
Branche : `feat/SF-176-10-a-qui-la-main`. Statut : `in-progress`. Date : 2026-10-06.

## Objectif
Une seule règle visible, sous la saisie, qui dit à tout instant qui doit agir — l'utilisateur, ou
l'agent (qui attend un geste, ou qui travaille) — pour qu'on ne se demande plus « message ou bouton ? » (G1).

## Comportement attendu

### Cas nominal
1. Indicateur sous la saisie, dérivé de l'état **déjà connu** du terminal (`handOf`), dans cet ordre :
   1. autorisation de commande en attente → « L'agent attend votre autorisation ↑ » ;
   2. question structurée F-164 en attente → « L'agent attend votre réponse ↑ » ;
   3. hors tour, le parcours attend un geste (proposition du guidé, diagnostic prêt, plan ou amendement
      à valider, clôture proposée) → « L'agent attend votre validation ↑ » ;
   4. un tour tourne → « L'agent travaille… » ;
   5. sinon → « À vous — écrivez ou cliquez une option ».
2. « ↑ » est une ancre : un clic ramène en vue l'invite, la question ou la bande du parcours.
3. Vocabulaire harmonisé : le bouton de la carte `demander` (F-164) devient **[Répondre et continuer]**,
   comme les boutons du parcours (« … et continuer », « … et lancer », SF-176-09).
4. Annoncé aux lecteurs d'écran (`role=status`, `aria-live=polite`).

### Cas d'erreur
| Situation | Comportement |
|---|---|
| parcours non chargé | les autres règles s'appliquent (jamais un faux « attend ») |
| chantier clos (Libre + CLOS) | aucune attente du parcours |
| cible absente au clic sur l'ancre | rien ne se passe |
| terminal en lecture seule | pas d'indicateur (pas de saisie) |

## Contraintes de validation
Aucune saisie.

## Critères d'acceptation
- [ ] À tout instant l'indicateur dit qui a la main ; il ne contredit jamais l'état serveur (dérivé, jamais supposé).
- [ ] Une autorisation ou une question en attente prime sur « travaille ».
- [ ] Un plan à valider affiche « L'agent attend votre validation ↑ » ; l'ancre ramène la bande.
- [ ] La carte F-164 dit [Répondre et continuer].

## Plan de test
- **Front** : `terminal-hand.spec.ts` (5 états, priorités, parcours, composant + ancre) ; spec F-164 existante.
- **Back** : aucun changement. **Isolation** : sans objet.
- Suites complètes front.

## Impacts
- Front : `terminal-hand.ts` (règle), `terminal-hand.component.ts` (affichage), `atelier-terminal.component.ts/.html`,
  `atelier-terminal-demande.component.html` (libellé). Aucun endpoint, aucune table.

### Préoccupations transversales
- Auth / tenant / plans / navigation : **non**.

## Hors périmètre
Notifications push (F-164-05 existant) ; indicateur dans la mosaïque.
