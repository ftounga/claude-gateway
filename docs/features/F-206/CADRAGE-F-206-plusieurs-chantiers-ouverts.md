# Cadrage F-206 — Plusieurs chantiers ouverts dans un même sujet

**Demande PO (2026-10-15)**, livraison autonome (vague). Constat vérifié sur `origin/main` 6e16f2c8 et en prod. Justesse avant coût ; charte `DESIGN_SYSTEM.md` (aucune couleur nouvelle) ; préfixe système stable (rien de volatil).

## Besoin
Dans un sujet (Agenor, Data Platform…), plusieurs chantiers avancent en même temps. Aujourd'hui un seul chantier peut être ouvert : les actions d'un autre chantier apparaissent « Hors plan ». **Décision PO** : « Hors plan » seulement si la suite ne relève d'**aucun** chantier ; si elle relève d'un autre chantier, elle est traitée comme telle (« Chantier 2 · Étape 3 ») et un mécanisme visuel permet de **basculer** sur cet autre chantier.

## Existant (F-176 SF-176-11, F-196)
`subject_journeys` = une ligne par terminal portant phase/plan/diagnostic du **seul** chantier courant ; `subject_journey_chantiers` = archives des clos ; `openChantier` remet le plan à zéro ; outils du parcours sans numéro de chantier ; GUIDE « un seul actif à la fois » ; `JourneyGate` et `JourneyTurnNote` lisent la ligne unique ; `ChantierAnchor.apply` valide contre le plan unique ; `NextStep` sans numéro de chantier.

## Décisions
- **Données** : l'état par chantier passe dans `subject_journey_chantiers` (status OPEN|CLOSED, phase, plan, plan validé, diagnostic, close_proposed…, unique `(workspace_id, number)`) ; `subject_journeys` garde le mode, la proposition et `focus_chantier`. Migration : la ligne courante devient un chantier OPEN ; archives conservées.
- **Outils** : paramètre `chantier` facultatif (défaut = focus) sur set_subject_plan, submit_diagnosis, update_plan_step, reopen_investigation ; `propose_guided_mode` ouvre un nouveau chantier **sans** clore les autres ; GUIDE mis à jour.
- **Porte** : une modification passe si au moins un chantier ouvert est en Exécution avec plan validé à jour ; refus qui nomme les chantiers.
- **Note de tour** : un bloc par chantier ouvert, focus en premier et détaillé, les autres résumés, borné.
- **Cap (F-196)** : suites `{chantier, etape}` validées contre le plan réel du chantier N ; libellé « Chantier N · Étape M » quand N ≠ focus ; « Hors plan » réservé à ce qui ne relève d'aucun chantier ouvert ; avancement par chantier.
- **Écran** : onglets/sélecteur de chantiers ouverts dans la bande du parcours (focus = chantier affiché) ; une suite d'un autre chantier porte un bouton **« Basculer sur le chantier N »** ; cartes de validation/clôture numérotées ; séparateur « ✓ Chantier N résolu » inchangé.
- Routes `/journey/chantiers/{n}/…` ; `GET /journey` rend `chantiers[]`.
- Préoccupation transversale : porte de modification (sécurité du poste) ⇒ lister et tester tous les appels de `JourneyGate`.

## Découpage
| SF | Objet | Risque |
|---|---|---|
| SF-206-01 | Données + migration + service multi-chantiers (ouvrir sans clore, focus), routes par chantier, tests | 🟠 |
| SF-206-02 | Outils + GUIDE + porte agrégée + note de tour multi-chantiers, tests (porte, préfixe stable) | 🟠 |
| SF-206-03 | Cap : `{chantier, etape}`, validation par chantier, « Chantier N · Étape M », avancement par chantier | 🟠 |
| SF-206-04 | Front : chantiers ouverts dans la bande (focus), bascule depuis une suite, cartes numérotées | 🟠 |
