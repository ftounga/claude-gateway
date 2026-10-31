# SF-226-01 — Suggérer le chantier parallèle, trois verbes, [Valider le plan] au tableau

**Feature :** F-226 · **Statut :** done (2026-10-31) · **Estimation :** 1 j · **Branche :** `feat/SF-226-01-suggestion-et-gestes`

## Objectif
Quand le PO est absorbé par un chantier, l'écran lui suggère, sans pop-up, de lancer à côté un autre chantier qui peut avancer seul ; les gestes disent clairement ce qu'ils font.

## Comportement
1. **Puce de suggestion** dans la barre du bas : « ⚡ Ch.N peut avancer seul · [Lancer à côté] · ✕ » (icône `bolt`).
   - **Quand** : aux moments creux seulement — hors tour, le chantier affiché attend l'utilisateur ou un tiers ; juste après « Valider et lancer » (jusqu'à la fin du tour qui suit) ; ou pendant une course du chantier affiché.
   - **Quoi** : un chantier non affiché dont « qui débloque » est l'outil (`unblocker.who === 'OUTIL'`), qui ne tourne pas (fils actifs relus par l'écran), avec moins de 4 courses en cours. Données déjà présentes, aucun appel de plus.
   - **Clic** : l'overlay « Lancer le chantier N à côté ? » existant. **✕** : masquée jusqu'à ce que l'état du chantier change. **Menu ⚙** : [Ne plus suggérer de chantier parallèle] / [Suggérer…] (par appareil). Jamais en lecture seule ni dans un volet de droite.
2. **Trois verbes** : **Afficher** (le focus), **Lancer à côté** (volet de droite), **Reprendre la main**.
   - Overlay : « Lancer le chantier N à côté ? », [Lancer à côté], [Afficher à côté] (ne peut pas partir), [Afficher ici] (depuis une suite).
   - Tableau : « Mettre au focus » → **Afficher** ; pour un chantier autre que celui du volet, « Lancer en autonome » → **Lancer à côté**, « Avancer jusqu'au blocage » → **Lancer à côté jusqu'au blocage**.
   - Suite vers un autre chantier : **Lancer à côté le chantier N** (pastille « l'outil peut avancer ») s'il peut avancer seul, sinon **Afficher le chantier N** (bascule directe, sans overlay).
3. **Onglets de la bande** : un clic sur l'onglet = **Afficher** ; un bouton explicite **Lancer à côté** à côté de chaque onglet non affiché.
4. **[Valider le plan]** sur la carte d'un chantier dont le plan attend, au tableau des chantiers : `POST …/chantiers/{n}/plan/validate` à la version vue, focus inchangé, aucun tour ; un refus dit pourquoi.

## Erreurs
| Situation | Comportement |
|---|---|
| Validation refusée (plan changé) | message du serveur en snackbar, tableau relu |
| Lecture seule | ni suggestion ni [Lancer à côté] |
| 4 courses en cours | pas de suggestion |

## Critères d'acceptation
- [x] Moment creux + chantier « outil » non affiché → suggestion ; clic → overlay (`source: 'suggestion'`).
- [x] Pas de suggestion quand l'outil avance sur le chantier affiché ; oui juste après « Valider et lancer ».
- [x] ✕ masque jusqu'au changement d'état ; chantier qui tourne ou 4 courses : rien ; coupée au menu ; lecture seule : rien.
- [x] [Valider le plan] au tableau : bonne route, bonne version.
- [x] Vocabulaire : Afficher, Lancer à côté, Afficher ici / à côté (specs mises à jour).

## Plan de test
`terminal-chantier-parallele-f226.spec` (7 tests) ; specs existantes mises au nouveau vocabulaire (`parallel-launch`, `terminal-next-steps`, `terminal-suites-colonnes`) ; suite front complète, build.

## Impact
`atelier.component` (fils actifs → terminal), `atelier-terminal` (suggestion, gestes, menu), `terminal-journey-strip`, `terminal-chantiers-board`, `terminal-next-steps`, `parallel-launch-dialog`, `atelier-terminal-dock.component.scss`. Aucune route nouvelle (route de validation par numéro existante). Préoccupations transversales : navigation — aucune route ; le menu gagne une préférence.

## Arbitrage (gate 🟠 réversible)
« Lancer à côté » remplace « Lancer en autonome » seulement quand le lancement passe par le volet de droite (un autre chantier que celui montré) ; sur le chantier montré, les libellés d'autonomie restent (le travail part ici). Les fils actifs viennent de la dernière relecture des fils par l'écran (au changement de focus) : une course démarrée ailleurs peut n'apparaître qu'à la relecture suivante.

## Hors périmètre
Lisibilité des onglets du tableau, onglets du split sur téléphone, split allégé (SF-226-02).
