# Mini-spec — F-117 / SF-117-01 La compaction automatique de l'historique

## Identifiant

`F-117 / SF-117-01`

## Feature parente

`F-117` — Le contexte d'un terminal ne déborde jamais

## Statut

`ready`

## Date de création

2026-09-14

## Branche Git

`feat/SF-117-01-compaction`

---

## Objectif

> En une phrase : quand le texte rejoué d'un fil de terminal dépasse un seuil de sécurité sous la
> fenêtre du modèle, résumer automatiquement les tours anciens en un bloc compact (via `AIProvider`,
> appel borné et isolé) et n'injecter que ce résumé + les tours récents dans ce qui repart au
> fournisseur — l'affichage, lui, garde tout.

---

## Comportement attendu

### Cas nominal

1. À l'ouverture d'un tour (`AtelierChatService.runLoop`), avant de bâtir la requête, on estime la
   taille en tokens du **texte rejoué** du fil (résumé déjà posé + messages depuis la frontière
   `chatThreadStartedAt`), par une heuristique caractères/token.
2. Si l'estimation dépasse le seuil configurable (`app.atelier.compaction.trigger-tokens`, défaut
   120 000 — marge sous la fenêtre 200 K), la compaction se déclenche :
   - les **N derniers tours** (`keep-recent-turns`, défaut 6 messages) sont gardés **intégralement** ;
   - tout ce qui précède (résumé existant compris) est **résumé** par un appel modèle dédié à
     `AiAgentProvider` (sans outils, borné), isolé par `user_id` + `host_id` ;
   - le nouveau résumé et la nouvelle frontière (`createdAt` du premier tour récent gardé) sont
     **persistés** sur le workspace (`chat_thread_summary`, `chat_thread_started_at`).
3. Au rejeu, si le workspace porte un résumé, un message de contexte est **injecté en tête** :
   `« [Résumé de la conversation précédente — conversation résumée jusqu'ici] … »`, suivi des tours
   récents. Le cache de prompt reste efficace : le résumé est un préfixe stable jusqu'à la prochaine
   compaction.
4. **L'affichage garde tout** : aucun message n'est supprimé en base ; seul ce qui repart au
   fournisseur est réduit (même invariant que le « nouveau départ » SF-39-04).
5. La consommation de l'appel de compaction est **agrégée aux compteurs du tour** : elle passe donc
   par le décompte d'usage existant (`recordUsage`), sans double comptage ni chemin de quota séparé.

### Cas d'erreur

| Situation | Comportement attendu | Effet |
|-----------|---------------------|-------|
| Compaction désactivée (`app.atelier.compaction.enabled=false`) | Aucune estimation, aucun résumé | Comportement d'avant F-117, à l'identique |
| Estimation sous le seuil | Aucun appel modèle | Rejeu inchangé |
| Pas assez de tours anciens à résumer | Aucune compaction | Rejeu inchangé (le repli SF-117-02 prendra le relais si ça déborde) |
| Appel modèle de résumé en échec | Best-effort : on **n'écrit ni résumé ni frontière**, le tour part avec l'historique complet | Aucun échec dur ; le tour se déroule comme avant |
| Workspace d'un autre utilisateur | 404 via `requireOwned` (inchangé, en amont) | Isolation garantie |

---

## Critères d'acceptation

- [ ] Un fil dont le texte rejoué dépasse le seuil est **compacté** : le prochain rejeu contient un
      message de résumé en tête + seulement les tours récents (rejeu réduit vs. historique complet).
- [ ] Le résumé et la frontière sont **persistés** sur le workspace ; un rejeu ultérieur réutilise le
      résumé sans le recalculer tant que le seuil n'est pas de nouveau franchi.
- [ ] L'affichage (historique en base) **n'est pas modifié** : aucun message supprimé.
- [ ] Sous le seuil, **aucun appel modèle** de compaction n'est fait (vérifié).
- [ ] Compaction désactivée → comportement strictement identique à avant (aucun résumé, aucun appel).
- [ ] L'appel de compaction est **isolé** : il ne porte que les messages du fil de ce `user_id` /
      `workspace_id` ; le journal mentionne `host_id`.
- [ ] Le cache de prompt n'est pas cassé : le résumé est injecté comme préfixe stable (le corps de
      requête, `cache_control`, le retry 429/529 et le décompte d'usage restent inchangés).
- [ ] Un échec de l'appel de résumé ne casse pas le tour (best-effort).

---

## Périmètre

### Hors scope (explicite)

- Le **repli sur 400 « prompt too long »** (SF-117-02) : ici la compaction est **proactive** (par
  estimation) ; le filet réactif est la subfeature suivante.
- Le **nouveau départ visible / suggéré** (SF-117-03).
- Le reaper de sessions et les fuites mémoire (SF-117-04).
- La fenêtre 1 M beta, le streaming (F-116), l'effort (F-118).

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| `workspaces.chat_thread_summary` | `null` | `null` = aucun résumé, rejeu complet — comportement d'avant F-117 pour tous les projets existants |

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format | Normalisation |
|-------|-------------|-------------|--------|---------------|
| `chat_thread_summary` | Non | TEXT (clob) | texte libre produit par le modèle | `null`/blanc = pas de résumé |
| `app.atelier.compaction.enabled` | Non | — | booléen, défaut `true` | absent ⇒ `true` |
| `app.atelier.compaction.trigger-tokens` | Non | — | entier > 0, défaut 120 000, plafond de sécurité | ≤ 0 ⇒ défaut |
| `app.atelier.compaction.keep-recent-turns` | Non | — | entier ≥ 2, défaut 6 | < 2 ⇒ défaut |

---

## Technique

### Endpoint(s)

Aucun nouvel endpoint : la compaction est interne à la boucle `AtelierChatService.runLoop`.

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `workspaces` | ALTER (add column) + UPDATE | nouvelle colonne `chat_thread_summary` ; frontière `chat_thread_started_at` déjà existante |

### Migration Liquibase

- [x] Oui — `107-workspaces-chat-thread-summary.xml` (H2 + PostgreSQL, colonne nullable, `dropColumn` en rollback)

### Composants Angular

- Aucun (backend uniquement).

---

## Plan de test

### Tests unitaires

- [ ] `AtelierCompactionService` — un fil au-dessus du seuil est compacté : résumé + frontière
      persistés, appel modèle fait une fois, seuls les tours anciens résumés.
- [ ] `AtelierCompactionService` — sous le seuil : aucun appel modèle, aucune écriture.
- [ ] `AtelierCompactionService` — appel modèle en échec : aucune écriture (best-effort).
- [ ] `AtelierChatService` (mémoire) — après compaction, le rejeu contient le message de résumé en
      tête + seulement les tours récents (rejeu réduit vs. historique long).
- [ ] `AtelierChatService` — compaction inerte (service absent / désactivé) : rejeu identique à avant.

### Tests d'intégration

- [ ] Contexte Spring démarre avec la nouvelle propriété et la nouvelle colonne (migration jouée sur
      base propre H2).

### Isolation workspace

- [x] Applicable — l'appel de compaction ne lit que les messages filtrés par `workspace_id` +
      `user_id` (repository) ; `requireOwned` reste en amont dans `runLoop`.

---

## Dépendances

### Subfeatures bloquantes

- Aucune (première subfeature de F-117).

### Questions ouvertes impactées

- Aucune (`docs/OPEN_QUESTIONS.md` inchangé).

---

## Notes et décisions

- **D1 — Persistance sur le workspace, pas de nouvelle table.** La frontière `chatThreadStartedAt`
  existe déjà sur `workspaces` ; le résumé la complète naturellement (une colonne `TEXT` nullable).
  Une table séparée n'apporterait rien : un seul résumé courant par fil.
- **D2 — Injection par préfixe stable.** Le résumé est injecté comme premier message `user` de
  contexte, **après** la consigne système (cachée) : il devient un préfixe stable, le cache de prompt
  n'est pas cassé.
- **D3 — Consommation agrégée au tour.** L'appel de compaction renvoie ses tokens ; `runLoop` les
  ajoute à ses compteurs → un seul chemin de décompte (`recordUsage`), pas de double comptage.
- **D4 — Injection par mutateur (setter `@Autowired(required=false)`).** `AtelierChatService` a de
  nombreux constructeurs ; on suit le motif `clientMailTool` pour ne toucher à aucun d'eux — service
  absent = compaction inerte (comportement d'avant F-117).
- **D5 — Best-effort.** Un échec de résumé ne doit jamais tuer un tour : la compaction est une
  optimisation de contexte, pas une étape obligatoire du tour.
