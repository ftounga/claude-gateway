# Mini-spec — F-117 / SF-117-09 : voir (et corriger) la passation

## Identifiant

`F-117 / SF-117-09`

## Feature parente

`F-117` — Le contexte d'un terminal ne déborde jamais (rouverte le 2026-10-11, cadrage
`CADRAGE-F-117-voir-la-passation.md`)

## Statut

`ready`

## Date de création

2026-10-11

## Branches Git

- `feat/SF-117-09-voir-la-passation-back` (contrat + service, mergé en premier)
- `feat/SF-117-09-voir-la-passation-front` (écran)

---

## Objectif

Rendre **lisible et corrigeable** le résumé de compaction (« la passation ») que la compaction
automatique écrit déjà dans `workspaces.chat_thread_summary` et réinjecte en tête du rejeu — sans
nouvelle table, sans nouvel appel modèle.

---

## Comportement attendu

### Cas nominal

1. `GET /api/workspaces/{id}/chat/handoff` → `200 { summary: string | null, length: number }`.
   `summary` = le résumé ancré tel qu'il sera réinjecté au prochain tour ; `null` s'il n'y en a pas.
2. `PUT /api/workspaces/{id}/chat/handoff` corps `{ summary: string }` → le texte (nettoyé des
   blancs de bord) **remplace** `chat_thread_summary` ; la réponse rend la nouvelle passation. Le
   tour suivant réinjecte le texte corrigé (`AtelierCompactionService.summaryPrefix`, inchangé) ;
   une compaction ultérieure fusionnera à partir du texte corrigé (incrémentale, inchangée).
3. Journal : `INFO` « passation corrigée » avec `workspace`, `poste`, longueurs avant/après —
   **jamais le contenu**.
4. Front : lien **« Voir la passation »** dans le panneau `/contexte` (ligne « Résumé ancré » quand
   présent) et dans le marqueur « Conversation compactée · N tours » (fil + marqueur de compaction
   manuelle). Il ouvre un panneau lisible (Markdown rendu, 5 rubriques) avec **« Corriger »** →
   zone d'édition (`mat-form-field` outline), **Enregistrer / Annuler**, compteur de caractères.
5. Lecture seule (tuile mosaïque) : consultation possible, **pas** de bouton « Corriger ».

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Projet d'autrui / inexistant (GET ou PUT) | 404 indiscernable (`requireOwned`) | 404 |
| Pas d'accès terminal (plan) | Refus existant `requireTerminalAccess` | 403 |
| PUT alors qu'aucune passation n'existe | Refus : on corrige, on ne crée pas | 409 `no_handoff` |
| PUT pendant un tour vivant (local ou pair) | Refus : réessayer à la fin du tour (sinon la sauvegarde du tour écraserait la correction) | 409 `turn_in_progress` |
| PUT texte vide / blanc / absent | Refus explicite | 400 `invalid_handoff` |
| PUT texte > 40 000 caractères | Refus explicite | 400 `invalid_handoff` |
| PUT texte qui ne porte plus les 5 titres du gabarit, dans l'ordre | Refus explicite (le gabarit garde la fusion incrémentale fiable) | 400 `invalid_handoff` |
| GET sans passation | `summary: null` ; le front n'affiche aucun lien | 200 |

---

## Contraintes de validation

| Champ | Règle |
|-------|-------|
| `summary` (PUT) | non nul, non blanc après `strip`, ≤ 40 000 caractères (prod observée : 10 000–17 000), contient `## Objectif`, `## Fichiers`, `## Décisions et faits établis`, `## État courant`, `## Prochaines étapes` dans cet ordre (`AtelierCompactionService.looksSectioned`) |

---

## Critères d'acceptation

- [ ] GET rend le résumé du propriétaire ; `null` sans résumé.
- [ ] PUT remplace le résumé ; le `summaryPrefix` suivant porte le texte corrigé.
- [ ] Projet d'autrui → 404 sur GET et PUT (isolation `user_id`).
- [ ] PUT refusé : vide (400), trop long (400), hors gabarit (400), sans résumé (409), tour vivant (409).
- [ ] Aucun contenu de résumé dans les journaux.
- [ ] Front : « Voir la passation » visible seulement si un résumé existe ; panneau Markdown ; « Corriger »
      absent en lecture seule ; Enregistrer désactivé si invalide ; erreurs serveur dites en snackbar.
- [ ] `--cg-*` uniquement ; cibles ≥ 44 px ; `ng build` et tests verts.

---

## Plan de test

- **Unitaires** (`AtelierThreadHandoffServiceTest`) : lecture avec/sans résumé ; correction nominale
  (strip, sauvegarde) ; refus vide, trop long, hors gabarit, sans résumé, tour vivant ; 404 d'autrui.
- **Intégration** (`AtelierThreadHandoffApiIntegrationTest`, MockMvc) : GET propriétaire ; GET d'autrui
  404 ; PUT nominal puis GET relit ; PUT d'autrui 404 (et résumé d'Alice intact) ; PUT hors gabarit 400 ;
  PUT sans résumé 409.
- **Front** : service HTTP (URL, verbe) ; composant passation (rendu, Corriger/Annuler/Enregistrer,
  lecture seule sans Corriger, validation gabarit) ; `/contexte` montre le lien seulement si présent.

---

## Impacts

- **Tables** : aucune nouvelle ; lecture/écriture de `workspaces.chat_thread_summary` (existante).
- **Endpoints** : `GET` + `PUT /api/workspaces/{id}/chat/handoff` (nouveaux).
- **Composants** : `AtelierThreadHandoffService` (nouveau), `AtelierChatController`,
  `GlobalExceptionHandler` ; front `AtelierContextService`, panneau `/contexte`, nouveau panneau
  passation, terminal (marqueurs de compaction).

### Préoccupations transversales

- [ ] Auth / Principal — non (réutilise `CurrentUser` + `requireTerminalAccess`).
- [ ] Contexte tenant — non (réutilise `requireOwned`).
- [ ] Plans / limites — non.
- [ ] Navigation / routing — non (panneau dans le terminal, aucune route).

---

## Hors périmètre

- Régénérer le résumé par le modèle (« Compacter maintenant » existe déjà, F-162).
- Historique des versions de la passation / annulation d'une correction.
- Ancres « (tour N) » cliquables (cadrage : « si simple ») — reportées : aucun index tour → message
  n'est exposé côté écran ; arbitrage tracé.
- Création d'une passation ex nihilo (PUT sans résumé existant).
