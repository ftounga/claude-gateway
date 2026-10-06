# Mini-spec — F-178 / SF-178-04 — Le poste sait où chercher

## Identifiant
`F-178 / SF-178-04` — feature parente `F-178` (cadrage, décisions D4 + D5). Branche :
`feat/SF-178-04-poste-sait-ou-chercher`. Statut : `in-progress`. Date : 2026-10-06.

## Objectif
Au terminal du poste, l'agent prend d'abord les bons chemins (recall portée poste, `sujets_etat`, lectures
de l'application) avant de fouiller les fichiers, et cite sa source ; on le **mesure**.

## Comportement attendu

### Cas nominal
1. **Doctrine** `HOST_LOOKUP_DOCTRINE` (littéral stable, cache F-134) injectée dans la consigne système du
   **terminal du poste seulement**, à la suite de l'aiguillage et de la passation : passé / autre sujet →
   `recall` portée « poste » ; état → `sujets_etat` ; Teams/réunions → `radar_*` s'ils sont offerts ; pages ;
   consommation ; puis seulement les fichiers ; **citer sujet + date** ; dire « rien trouvé » plutôt que supposer.
2. **D5** : rien n'est joint d'office au message — la doctrine oriente vers des outils à la demande.
3. **Mesure** `GET /api/admin/poste-tools/measure?pivot=AAAA-MM-JJ&days=N` (admin, défaut 7 j, max 90) :
   avant / après le pivot, sur les terminaux du poste de l'utilisateur — appels par outil (`recall_poste`,
   `recall_fil`, `sujets_etat`, `radar_resume`, `radar_sujets`, `pages_lister`, `page_lire`,
   `compte_consommation`, `bash`, `read_file`, `grep`) lus dans `atelier_messages.tool_trace` (≤ 5 000
   messages par fenêtre), tours et coût moyen d'un tour (`usage_turns`), notes de lecture.

### Cas d'erreur
| Situation | Comportement |
|---|---|
| Non administrateur | 403 |
| `pivot` illisible | 400 `invalid_pivot` |
| Trajectoire illisible | ignorée (trajectoire vide) |
| Aucun terminal du poste | fenêtres à zéro |

## Contraintes de validation
`pivot` ISO `AAAA-MM-JJ` ; `days` 1–90 (défaut 7).

## Critères d'acceptation
- [x] La doctrine est dans la consigne du terminal du poste, absente d'un sujet.
- [x] La mesure compte recall poste / fil et `sujets_etat` des seuls terminaux du poste de l'admin.
- [x] Coût moyen par tour avant / après ; endpoint réservé à l'admin.

## Plan de test
- `AtelierChatServiceSystemPromptTest` (+2) : présence au poste, absence dans un sujet.
- `PosteToolsMeasureIntegrationTest` (2, H2 + MockMvc) : comptage et isolation (sujet et autre compte exclus),
  coût moyen ; 403 / 200 / 400.
- Non-régression : suite complète backend.

## Impacts
Backend : `AtelierChatService` (doctrine) ; `atelier.poste` (`PosteToolsMeasureService`,
`PosteToolsMeasureController`). Endpoint ajouté : `GET /api/admin/poste-tools/measure`. Aucune table, aucune
migration.

### Préoccupations transversales
- **Contexte tenant** : ✔ — `PosteToolsMeasureService` : toutes les requêtes filtrées `user_id` (terminaux,
  tours, trajectoires) de l'administrateur appelant (`CurrentUser`).
- **Auth** : ✔ — nouvel endpoint admin ; garde `AdminService.assertAdmin()` (même patron que F-174/F-176),
  testée 403. Aucun endpoint existant modifié.

## Hors périmètre
Écran de la mesure (lecture par l'API, comme F-174/F-176) ; jonction d'office de contexte (D5).

## Arbitrages (réversibles)
- Comptage depuis la trajectoire d'outils persistée (les outils côté gateway n'entrent pas dans `runner_audit`).
- Fenêtre par défaut 7 jours (mesure J+7, comme F-173/F-174).
