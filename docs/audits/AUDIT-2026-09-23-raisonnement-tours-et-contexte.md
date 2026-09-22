# Audit — Le raisonnement : nombre de tours et taille du contexte

> Demandé par le PO le 2026-09-23 : *« j'ai l'impression que Claude Code est bien plus puissant… elle
> rend des résultats justes mais il faut faire pas mal de tours »*. Objectif : rendre le raisonnement
> **plus rapide (moins de tours)** en s'appuyant sur **des données récentes** (les correctifs de perf
> récents sont acquis, ne pas ré-auditer du déjà-corrigé). Le PO est **ouvert à stocker/précalculer
> plus** si cela accélère. **Contrainte dure : le cluster `legalcase-shared` est à capacité — aucun
> nouveau composant cluster.**
>
> **Méthode** : mesures sur la base de production (7 jours au 2026-09-22, focus sur la fenêtre où le
> cache fonctionne, 21–22/09) + lecture du code (`fichier:ligne`). **Complète** l'audit du 2026-09-21
> (`AUDIT-2026-09-21-raisonnement-savoir-conformite.md`), dont les features F-135→140 sont **livrées**.

---

## 0. Verdict

Le raisonnement est **juste mais coûteux en allers-retours** : ~**21 outils par tour**, un contexte
moyen de **~1,18 M tokens/tour** (p90 ≈ 2 M) le 22/09. Ce n'est **pas** une faiblesse du moteur — les
correctifs récents tiennent — c'est un **reliquat d'affinages** au-dessus des features déjà livrées.

**Ce qui est déjà bon (ne pas re-cadrer) :** F-134 (cache : part relue portée à ~99 %, mesurée ici à
**85 % les 21–22/09** — les jours à **0 %** avant le 20/09 sont le problème *déjà corrigé*, écartés).
F-135→140 (le savoir entre dans le contexte : `HostMapOutline`, `HostFactLookup`, profils métier,
péremption, mesure). SF-39-21/22 (`explore` parallèle lecture-seule). Compaction bornée.

---

## 1. Les mesures (récent, prod)

| Mesure (fenêtre cache OK, 21–22/09) | Valeur |
|---|---|
| Contexte moyen / tour (22/09) | **1 183 112 tokens** (p90 7j ≈ 1 970 148 ; max 5,6 M) |
| Tokens **plein tarif** / tour (22/09) | **174 131** (85 % relu du cache) |
| Outils / tour (3j) | **21,3** |
| Demandes utilisateur ≈ tours (3j) | 81 demandes / 80 tours → **1 tour agentique par demande**, ~21 outils dedans |
| Coût 7j (1 utilisateur, 4 espaces) | 151,34 $ |

### Distribution des outils (3 jours)
| Outil | Appels | % | Note |
|---|---|---|---|
| `governance_map_read` | 839 | **49,3 %** | **Lectures internes de la GATEWAY** (juge de fin de tour, intégrité, écran) — **PAS** l'agent (durée 1 ms, gateway-side). |
| `bash` | 424 | 24,9 % | Le vrai outil de travail (dur. 2176 ms). |
| `teams_radar_verify` | 166 | 9,7 % | 3560 ms. |
| `screen_list_files`/`folders` | 136 | 8,0 % | Exploration d'arborescence. |
| `read_file`/`edit_file`/`write_file`/`grep` | ~44 | ~2,6 % | Ciblés. |

**Correction d'une erreur d'un premier passage :** `governance_map_read` (49 %) **n'est pas l'agent qui
relit la carte** — ce sont les lectures internes de la gateway (déjà établi par l'audit du 21/09,
corroboré par la durée 1 ms). Les « beaucoup de tours » **ne viennent donc pas de là**.

### Fiabilité des outils (3 jours)
OK 86,7 % · **ERROR 13,1 %** · TIMEOUT 0,2 %. Point saillant : **`governance_map_read` échoue à 25 %**
(214/839). Même côté gateway, un défaut à corriger.

---

## 2. Où partent réellement tours & tokens (code)

- **Un tour agentique = 1 demande** ; il contient ~21 allers-retours modèle↔outils (`runLoop`,
  `AtelierChatService.java:1385`). Ce qui force des allers-retours : la **discipline « lire avant
  d'agir »** (`INVESTIGATION_DISCIPLINE` `:192`/`:4269`, `fileStateHints` `:1743`) — **voulue**
  (justesse), c'est le gros du volume ; la chaîne interpréter-résultat (inhérente) ; les checkpoints de
  fin de tour (F-50 `:1554-1585`) ; le verrou Teams (`:1674-1680`) ; la synthèse forcée (`:1605`).
- **Le contexte** : historique **texte intégral** rejoué (borné par compaction à `triggerTokens=120k`)
  + traces d'outils des **12 derniers tours** + préfixe système (rôle, ~8 doctrines, `hostOutline`,
  **CLAUDE.md verbatim**, gouvernance/profils, **jusqu'à 50 skills** `:405`). Le cache ramène l'essentiel
  à ~1/10 du tarif — le coût réel est **les tours où le préfixe/contexte mute** (cache froid).

## 3. Reliquats réels (après F-135→140)

1. `stepEffort=low` en continuation (`AtelierProperties.java:172`) → sous-raisonnement possible →
   auto-corrections → tours de rattrapage.
2. Profil métier actif **n'écrase pas** l'amorce de rôle « assistant de développement »
   (`:4251`/`:4384`) → effet dilué ; **jamais activé/testé** en prod.
3. Catalogue de skills à **50 fichiers** relu par message (`:4393-4416`) → latence de démarrage
   (round-trips runner synchrones avant le 1er token) ; CLAUDE.md relu du runner, non caché gateway.
4. `HostMapOutline` n'injecte que **titres** (`HostMapOutline.java:44-78`) ; le contenu de
   `STATE.md`/`PLAN-ACTION.md` (déjà en base, `HostMapStore`) reste lu par l'agent (F-136/137 ne
   couvrent que l'outline + les faits par entité).
5. Rappel de faits **keyword-only** (`HostFactLookup.java:81-100`) : une question sans terme distinctif
   → pas de match → ré-exploration. **Décision 09-21 : embeddings reportés au-delà de ~20 000 faits**
   (on est à ~3 000). À garder en réserve, pas à faire maintenant.
6. `governance_map_read` échoue 25 % (gateway-side) — défaut à diagnostiquer.
7. Pas d'index de repo persistant (`grep`/`glob` runner à chaque appel `:4181-4203`).
8. Pas de mémoire de résolutions (question → conclusion) réutilisable.

## 4. Infra / ressources
**Aucun changement d'infra.** Tous les leviers sont prompt / contexte / configuration dans le backend
existant. « Stocker plus » = croissance **modeste** de Postgres (copies de carte déjà là ; vecteurs
négligeables à 3 000 faits) + **plus de tokens/requête** (coût, absorbé ~85–99 % par le cache). Aucun
nouveau pod, aucun nœud plus gros — cohérent avec le cluster à capacité.

## 5. Suite
Cadrage **F-148 « Performance du raisonnement — affinages »** (`docs/features/F-148/`), **9 leviers**
(embeddings/RAG retiré, en réserve tant que < ~20 000 faits), dont plusieurs sont des **extensions** de
F-135→140 (dits comme tels), zéro impact infra.
