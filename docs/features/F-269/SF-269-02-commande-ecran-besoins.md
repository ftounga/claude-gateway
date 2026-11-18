# Mini-spec — [F-269 / SF-269-02] Écran Besoins : filtrer, afficher et saisir une commande

## Identifiant
`F-269 / SF-269-02`

## Feature parente
`F-269` — Commandes pour Claude Code dans le registre des besoins

## Statut
`done`

## Date de création
2026-11-18

## Branche Git
`feat/SF-269-02-commande-ecran-besoins`

---

## Objectif
Dans l'admin, section Besoins, le PO voit et filtre les commandes et en saisit une directement, sans passer par l'agent.

## Comportement attendu

### Cas nominal
- Filtre **Type** à côté du filtre Statut : Tous / Bugs / Évolutions / Commandes (param `kind`).
- Une COMMANDE s'affiche avec l'icône `terminal` et le libellé **« Commande »** ; dans le détail, le titre
  de section est « La commande (telle quelle) » au lieu de « Ses mots ».
- Bloc **« Nouvelle commande pour Claude Code »** en tête de carte : zone de texte (8 000 car. max) + bouton
  [Enregistrer] → `POST /api/admin/app-requests/commands` ; succès : snackbar « Commande enregistrée — Claude
  Code l'exécutera à son prochain démarrage. », champ vidé, liste rechargée.
- La carte du terminal (`besoin-card`) dit « Commande pour Claude Code · titre » pour une COMMANDE.

### Cas d'erreur
| Situation | Résultat |
|---|---|
| Texte vide / blanc | bouton [Enregistrer] désactivé |
| Refus serveur (403, 400) | snackbar avec le message du serveur, texte conservé |

## Critères d'acceptation
- [ ] CA1 — le filtre Type « Commandes » appelle `list(status, 'COMMANDE')`.
- [ ] CA2 — libellé « Commande » et icône `terminal` pour `kind = COMMANDE`.
- [ ] CA3 — [Enregistrer] envoie le texte tel quel, vide le champ et recharge ; désactivé si vide.
- [ ] CA4 — une erreur garde le texte et affiche le message.
- [ ] CA5 — conforme `DESIGN_SYSTEM.md` (jetons `--cg-*`, Material, cibles ≥ 44 px), 360 px sans débordement.

## Périmètre — hors scope
- Modifier le texte d'une commande enregistrée (les mots ne sont jamais réécrits, règle F-205).
- Pièces jointes à une commande saisie directement.

## Technique
- `app-request.service.ts` (`list(status, kind)`, `createCommand`, libellé), `atelier.models.ts` (type `AppRequestKind`),
  `admin-besoins.component.ts`, `besoin-card.component.ts`.

### Préoccupations transversales
- Navigation : non (aucune route nouvelle). Auth / tenant / plans : non (routes admin existantes, garde serveur).

## Plan de test
### Unitaires
- `admin-besoins.component.spec.ts` : filtre Type, libellé Commande, saisie (succès, vide, erreur).
- `app-request.service` : `kind` passé en paramètre, `createCommand` POST.
- `besoin-card.component.spec.ts` : intitulé d'une COMMANDE.
### Isolation / refus
- Couverts côté serveur (SF-269-01 : 403 non-admin, isolation `user_id`).
