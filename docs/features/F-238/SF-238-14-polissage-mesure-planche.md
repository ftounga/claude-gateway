# Mini-spec — [F-238 / SF-238-14] Polissage final, mesure, planche

## Identifiant
SF-238-14

## Feature parente
F-238 — Architecture en 3D, itération 2 « Jarvis la raconte » (cadrage §10.1 règles F-236 §7.3, §17, §20)

## Statut
done

## Date de création
2026-11-15

## Branche Git
`feat/SF-238-14-polissage-mesure`

---

## Objectif

Mesurer l'usage réel de l'architecture en 3D (sans contenu), régler le rythme de la caméra (anti mal des
transports) et livrer la planche finale des itérations 1 et 2 pour le visa du PO.

## Comportement attendu

### Cas nominal
1. **Mesure** (`UsageEvent RENDER` étendu, vocabulaire fermé partagé front ↔ passerelle, aucun contenu) : `a_3d`
   scènes 3D posées, `a_ai` dont d'initiative, `a_sec` secondes en 3D ; replis 2D par cause `a_2d_u` (univers 2D),
   `a_2d_g` (graphe absent), `a_2d_w` (WebGL), `a_2d_p` (performance), `a_2d_m` (« en 2D ») ; `a_exp` éclatements,
   `a_fold` / `a_unf` replis / déplis, `a_foc` focus, `a_flow` flux allumés par un ordre, `a_view` vues, `a_narr`
   phrases entendues qui allument, `a_amb` cibles ambiguës (question posée). Les ordres restent comptés par origine
   (`n_loc` voix locale, `n_ai` IA, `n_tch` toucher).
2. **Rythme** : au plus un grand déplacement de caméra par 2,5 s (F-236 §7.3) — un second qui suit de près ralentit
   au lieu d'enchaîner deux élans ; l'orbite au doigt n'est jamais ralentie.
3. **Planche finale** : captures headless (`--use-angle=swiftshader`, sans `--hide-scrollbars`, 1920 × 1080 et
   390 × 844) de `/jarvis` réel (API simulée) et du banc, dans `~/.local/share/claude-gateway/captures-f238/`, avec
   une page `planche-f238-iterations-1-2.html`.
4. **Lecture J+7** : requête fournie (compteurs `a_*` de `jarvis_usage_days.render_counts`), à lire le 22/11.

### Cas d'erreur
| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Clé inconnue dans un compte-rendu | ignorée par la passerelle (inchangé) | — |
| Détail plus long que 2 000 caractères | tronqué comme avant (inchangé) | — |

## Critères d'acceptation
- [ ] Clés `a_*` dans le vocabulaire fermé des deux côtés (tests) ; le store compte ordres et questions (test).
- [ ] Rythme : `paceMove` (test).
- [ ] Planche produite ; **visa PO** (relayé) ; lecture J+7 planifiée.

## Périmètre — hors scope
Nouvelle table (aucune) ; tableau de bord de la mesure.

## Valeurs initiales
Sans objet.

## Contraintes de validation
| Champ | Règle |
|---|---|
| clés | vocabulaire fermé `RenderCounts.KEYS` |

## Technique
`jarvis-piece-measure.ts`, `RenderCounts.java`, `jarvis-piece-store.ts` (`archKey`),
`jarvis-piece-architecture.component.ts`, `jarvis-piece-viewer.component.ts`, `architecture-stage.component.ts`
(`narrated`), `architecture-stage-director.ts` (`paceMove`), `engine/architecture-scene-3d.ts`.

## Plan de test
### Tests unitaires
`architecture-measure.spec.ts` ; `RenderCountsTest`.
### Tests d'intégration
Banc `/jarvis` (captures de la planche).
### Isolation utilisateur
Non applicable (compteurs du compte, sans contenu, chemin existant).

## Préoccupations transversales
Aucune (pas de consigne, pas de route, pas de quota).
