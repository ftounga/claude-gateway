# Cadrage F-196 — Cap au service du chantier

**Demande PO (2026-10-12)** : en mode guidé (chantier F-176), les suites de Cap (F-192) doivent être liées au chantier. Un approfondissement n'est pas interdit mais doit être **visible** et venir de préférence **après** la résolution du chantier ; la résolution doit être **marquée visuellement** (« le chantier est réglé, à partir d'ici on va plus loin »). Motif : à force de cocher des suites intéressantes, on s'égare sans s'en rendre compte.
**Livraison** : autonome, APRÈS F-195 (même zone : consigne `NEXT_STEPS_DOCTRINE`, panneau Cap). **Règle absolue** : justesse avant coût.

## Constat (prod, sujet WAF `2185878b…`, 2026-10-12)
- Chantier en GUIDÉ / EXÉCUTION, plan v1 validé, 10 étapes : 2 vérifiées, 1 faite, **7 à faire** (dont l'étape 2, policy NAP V12, un des trois points du besoin).
- Suites Cap 18:54 → 19:07 : rétention/KMS des logs WAF, export SIEM, metric filters, alarmes, jetons dans les logs — **aucune ne correspond à une étape du plan** ; le PO en a coché plusieurs, puis a dû recadrer à 19:10 (« j'ai ouvert ce sujet avec un besoin bien défini… »).
- Cause : `JourneyTurnNote` (préfixé à la consigne du tour) donne bien phase + plan au modèle, mais la consigne Cap (`NEXT_STEPS_DOCTRINE`, F-192) ne relie jamais les suites au plan ; le panneau ne distingue pas « avancer le chantier » de « creuser ».

## Décisions (PO, 2026-10-12)
1. **Rattachement** : en mode GUIDÉ, chaque suite porte soit le numéro de l'étape du plan qu'elle fait avancer (ou débloque), soit est un **approfondissement** (hors plan). La gateway **vérifie** contre le plan réel (`SubjectJourney.planJson`) : numéro absent/inexistant ⇒ approfondissement. Jamais confiance aveugle au modèle.
2. **Le chantier d'abord** : tant que le chantier n'est pas clos, la consigne demande de proposer d'abord la prochaine étape à faire et ce qui la débloque ; **au plus un** approfondissement, en dernière position. La gateway **ordonne** (étapes d'abord) et **borne** (1 approfondissement max avant clôture).
3. **Visible** : étiquette de rattachement « Étape N · <titre court> » ; approfondissement = étiquette ambre (§12) « Hors plan · approfondissement » ; en-tête du panneau « Chantier : X/Y étapes » ; cocher un approfondissement pendant un chantier ouvert affiche une ligne « Le chantier n'est pas terminé : il reste N étapes » (n'empêche rien).
4. **Marquer la résolution** : à la clôture du chantier (phase CLOS, flux F-176 existant), un séparateur dans le fil : « ✓ Chantier N résolu — ce qui suit va au-delà du besoin de départ » (persisté/rejouable à l'historique, au bon endroit du fil). Après lui, les approfondissements sont libres ; Cap peut proposer d'ouvrir un nouveau chantier.
5. **Mode Libre** : inchangé (aucun rattachement, aucun badge).
6. Préfixe stable : la règle générale va dans la consigne système (stable) ; tout ce qui dépend du chantier reste dans la note de tour (`JourneyTurnNote`).

## Découpage
| SF | Objet | Risque |
|---|---|---|
| **SF-196-01** | Backend : champ `step` (entier ou null) dans le bloc `<<suites>>` ; consigne stable « en mode guidé, rattache chaque suite à une étape ; chantier d'abord ; au plus un approfondissement, en dernier » ; validation contre le plan (`scope` = CHANTIER / APPROFONDISSEMENT, `stepIndex`, `stepTitle` court), tri et borne côté gateway ; exposition dans `nextSteps` (flux `done` + historique) avec l'avancement `{done, total}` du chantier ; Libre ⇒ champs absents. Tests : rattachement valide/invalide, tri, borne, Libre inchangé, préfixe stable. | 🟠 |
| **SF-196-02** | Front panneau Cap : étiquette d'étape, étiquette ambre « Hors plan », en-tête « Chantier : X/Y étapes », ligne d'avertissement à la coche d'un approfondissement chantier ouvert ; charte (aucune couleur nouvelle, ambre §12). | 🟢 |
| **SF-196-03** | Séparateur « ✓ Chantier N résolu » dans le fil à la clôture (persisté, rendu à l'historique, AA) ; après clôture, Cap sans badge « hors plan » et peut proposer un nouveau chantier (consigne). | 🟠 |

## Critère de réussite
Sur un chantier guidé ouvert, les suites affichées sont d'abord des étapes du plan ; un approfondissement est marqué et isolé ; la clôture laisse une trace visible dans le fil.

## Hors périmètre
- Interdire les approfondissements ; modifier le flux de clôture F-176 lui-même ; le mode Libre.
