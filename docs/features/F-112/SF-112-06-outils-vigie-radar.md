# Mini-spec — F-112 / SF-112-06 : Les outils Vigie et Radar

## Identifiant

`F-112 / SF-112-06`

## Feature parente

`F-112` — Le serveur MCP

## Statut

`ready`

## Date de création

2026-09-14

## Branche Git

`feat/SF-112-06-outils-vigie-radar`

---

## Objectif

Exposer sur le serveur MCP les outils **Vigie et Radar** (cadrage §5), relais des services Radar
existants, scopés à un **poste** (Vigie) et gardés par les périmètres `radar:lire` / `radar:ecrire`,
l'accès poste par poste et l'activation Vigie du poste.

---

## Comportement attendu

| Outil | Périmètre | Relaie |
|---|---|---|
| `radar_synchroniser` | `radar:ecrire` | `RadarSyncLauncher.start(MANUAL)` (→ **tâche** : rend `sync_id`) |
| `radar_resume` | `radar:lire` | `RadarBriefService.brief` |
| `radar_sujets` | `radar:lire` | `RadarReadService.subjects` |
| `radar_sujet` | `radar:lire` | `RadarReadService.subject` |
| `radar_donner_nouvelle` | `radar:ecrire` | `RadarNewsService.give` |
| `radar_clore_sujet` | `radar:ecrire` | `RadarClosureService.close` |
| `radar_lier_projet` | `radar:ecrire` | `RadarSubjectProjectService.link` |
| `radar_preparer_relance` | `radar:lire` | `RadarManagerAnswerService.prepare` |
| `radar_couverture` | `radar:lire` | `RadarReadService.syncs` |

Chaque outil : (1) vérifie le périmètre ; (2) exige `host_id` (UUID) ; (3) refuse si le poste n'est
pas accessible (poste par poste) ; (4) résout le `RadarScope` via `RadarScopeResolver.requireInVigie`
(possession du poste **et** activation Vigie) ; (5) relaie le service avec ce scope ; (6) rend un
contenu structuré, **contenu Radar marqué non fiable** (§6.3, il vient de Teams) et masqué (§6.4).

### Cas d'erreur

| Situation | Comportement |
|---|---|
| Périmètre absent | refus nommé |
| `host_id` absent/mal formé | erreur claire |
| Poste non accessible | refus « poste non accessible » |
| Poste non possédé / pas dans la Vigie | erreur claire (404 / host_not_in_space relayés) |
| `radar_synchroniser` poste hors ligne | erreur « poste hors ligne » (rien créé) |
| `radar_donner_nouvelle` texte vide | erreur claire |

---

## Critères d'acceptation

- [ ] Les 9 outils sont découverts avec annotations (lecture vs écriture).
- [ ] Un outil `radar:lire` refuse sans le périmètre ; un outil `radar:ecrire` de même.
- [ ] Poste non accessible / non possédé → refus, sans fuite d'existence.
- [ ] `radar_synchroniser` rend un `sync_id` (tâche) ou refuse si hors ligne.
- [ ] Le contenu Radar rendu est marqué `untrusted`.
- [ ] Isolation : le Radar d'un poste d'autrui est introuvable.

---

## Périmètre — hors scope

- Corrections structurelles (merge/split/alias), purge, export, planification, règles de fil,
  enregistrements : hors des 9 outils du cadrage §5 (l'app les garde).
- Ressources/prompts (SF-112-08).

---

## Contraintes de validation

| Champ | Obligatoire | Format |
|---|---|---|
| `host_id` | Oui | UUID |
| `subject_id` | Oui (`radar_sujet`, `radar_clore_sujet`, `radar_lier_projet`, `radar_preparer_relance`) | UUID |
| `project_id` | Oui (`radar_lier_projet`) | UUID |
| `text` | Oui (`radar_donner_nouvelle`) | non vide |
| `include_closed` | Non (`radar_sujets`) | booléen, défaut false |

---

## Technique

Aucun endpoint HTTP, aucune migration. `RadarScopeResolver.requireInVigie(userId, hostId)` (déjà
userId-based) fournit la garde Vigie sans `SecurityContext`. Contenu tiers converti en arbre JSON via
`McpToolSupport.okUntrusted` (masqué + marqué non fiable).

### Préoccupations transversales

- **Contexte tenant** : oui — `RadarScope(userId, hostId)` depuis le jeton ; `requireInVigie` vérifie
  possession + activation. Composants : `RadarScopeResolver`, `HostSpaceService`, services Radar
  relayés, `McpHostAccess`. Aucune route HTTP existante touchée.
- **Plans / limites** : oui — droit Vigie via l'activation du poste (`requireInVigie`).

---

## Plan de test

### Intégration (client SDK réel, périmètres)

- [ ] Découverte des 9 outils et annotations.
- [ ] `radar_sujets` sans `radar:lire` → refus.
- [ ] `radar_donner_nouvelle` sans `radar:ecrire` → refus.
- [ ] `host_id` d'un autre utilisateur → erreur (introuvable).
- [ ] Poste non activé en Vigie → erreur claire.
- [ ] `host_id` mal formé → erreur.

### Isolation utilisateur

- [ ] Applicable — `RadarScope` porte `user_id` du jeton ; test croisé A/B.

---

## Dépendances

- `SF-112-01→05` — done. F-99/F-100/F-106 (Radar, synchro, Vigie).

---

## Notes et décisions

- `radar_couverture` relaie `RadarReadService.syncs` (ce que chaque synchro a couvert), la couverture
  détaillée du matin restant dans `radar_resume` (brief). `radar_preparer_relance` relaie
  `RadarManagerAnswerService.prepare` (brouillon de réponse/relance sur un sujet).
- **Tâche** : `radar_synchroniser` rend un `sync_id` tout de suite ; le suivi se fait par relecture
  (`radar_couverture`/`radar_resume`), cohérent avec le modèle de tâche de SF-112-05.
