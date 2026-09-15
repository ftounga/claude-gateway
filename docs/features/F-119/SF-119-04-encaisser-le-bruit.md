# Mini-spec — [F-119 / SF-119-04] Encaisser le bruit sans conclure faux

> Base : `project-governance/templates/subfeature-template.md`.
> Cadrage : `docs/features/F-119/CADRAGE-F-119-justesse-de-l-agent.md` (Cause 4).

---

## Identifiant

`F-119 / SF-119-04`

## Feature parente

`F-119` — La justesse de l'agent : se tromper moins, se corriger moins

## Statut

`ready`

## Date de création

2026-09-15

## Branche Git

`feat/SF-119-04-encaisser-le-bruit`

---

## Objectif

> En une phrase : sur un `bash` en erreur/timeout, rendre au modèle la **sortie partielle déjà
> streamée** en plus de la note d'échec, et formuler un échec de **transport** runner
> (timeout/indisponible/nœud/protocole) comme explicitement « **non concluant** » — pas comme un
> résultat négatif — pour couper les conclusions hâtives (17 % des appels échouent derrière le proxy
> CAGIP).

---

## Comportement attendu

### Cas nominal

1. **`bash` en échec avec sortie partielle** : `bashOutcome` ne jette plus `result.streamed()` — il
   rend `"$ <commande>\n<sortie partielle bornée>[\n… (sortie tronquée)]\n\n<note>"`, en `isError`.
2. **Note « non concluant »** : pour un échec de transport (`runner_timeout`, `runner_unavailable`,
   `runner_not_on_this_node`, `runner_protocol_error`), la note est « Résultat non concluant : … Ce
   n'est pas un résultat négatif — réessaie, ou dis que tu n'as pas pu conclure ; n'en tire aucune
   conclusion. ». Appliqué aussi à `read_file` (lecture qui time out ≠ fichier absent).
3. **Succès inchangé** : un `bash` qui tourne (code de sortie ≠ 0 compris) garde exactement son format
   `"$ cmd\n<sortie>\n[code de sortie: N]"` (non-régression).

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| `bash` timeout **avec** sortie partielle | Sortie partielle + « … (sortie tronquée) » + note « non concluant » ; `isError` vrai |
| `bash` timeout/indispo **sans** sortie | Note « non concluant » seule ; `isError` vrai |
| `bash` refusé pour une **vraie** raison (outil non activé, argument invalide) | Message inchangé (pas « non concluant » — ce n'est pas un échec de transport) |
| `read_file` timeout/indispo | Note « non concluant » (pas « fichier introuvable ») |

---

## Critères d'acceptation

- [ ] Un `bash` en timeout avec sortie partielle remonte la sortie **et** la note ; `isError` vrai.
- [ ] Le libellé d'un échec de transport (`runner_timeout`/`unavailable`/…) dit « non concluant » et
      invite à réessayer / à le signaler.
- [ ] Un `read_file` en timeout dit « non concluant », pas un négatif.
- [ ] Le format d'un `bash` réussi (code 0 ou ≠ 0) est **inchangé** (non-régression).
- [ ] Un échec **non** transport (unsupported_tool, invalid_input) garde son message.
- [ ] `isError` reste vrai sur ces échecs → SF-119-01 ré-escalade l'effort au tour suivant.

---

## Périmètre

### Hors scope (explicite)

- Les autres SF de F-119.
- Une politique de retry automatique côté gateway (le modèle décide de réessayer ; on lui donne
  juste la bonne information).
- Reformuler les échecs des outils non-fichiers (Teams/Radar/pages) — hors du chemin
  d'investigation visé.

---

## Technique

### Composants impactés

| Composant | Opération | Notes |
|-----------|-----------|-------|
| `AtelierChatService.bashOutcome` | Branche `!ok` : sortie partielle + note « non concluant » | Cœur de la SF |
| `AtelierChatService.readOutcome` | Branche `!ok` : note « non concluant » pour transport | Cohérence |
| `AtelierChatService` | Helpers `inconclusiveNote`, `isInconclusiveFailure`, `boundBashBytes` | |

### Endpoint(s) / Tables / Migration

Aucun. Message rendu au modèle uniquement.

---

## Plan de test

### Tests unitaires

- [ ] `AtelierChatServiceRunnerTargetTest` — `bash` timeout avec sortie partielle → sortie + note ;
      `bash` timeout sans sortie → note « non concluant » ; `read_file` timeout → « non concluant » ;
      non-régression des succès (déjà couverte) ; échec non transport (unsupported) → message inchangé
      (déjà couvert).

### Tests d'intégration / isolation

- [ ] Non applicable — aucun accès données nouveau ; routage runner isolé `user_id`/`host_id`
      inchangé.

---

## Préoccupations transversales

- **Plans / limites / auth / tenant / navigation** : non. Aucune consommation nouvelle (on renvoie une
  sortie déjà captée), aucun endpoint, aucune route.

---

## Notes et décisions

- **Décision par défaut** : reformulation « non concluant » **toujours active** pour les échecs de
  transport (c'est une correction de justesse, pas un mécanisme risqué) ; pas de drapeau.
- **Transport vs vrai échec** : seuls les codes où *rien n'est prouvé* (timeout/indispo/nœud/protocole)
  deviennent « non concluant » ; un `invalid_input`/`unsupported_tool` garde son message (c'est un
  vrai refus, pas du bruit).
