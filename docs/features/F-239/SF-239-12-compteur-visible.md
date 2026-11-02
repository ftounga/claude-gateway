# Mini-spec — F-239 / SF-239-12 — Le compteur visible

## Identifiant
`F-239 / SF-239-12` · Feature parente `F-239` · Statut `in-review` · 2026-11-02 · Branche `feat/SF-239-12-compteur`

## Objectif
> Le PO voit discrètement ce que Jarvis a coûté aujourd'hui et ce mois-ci, en euros, par poste : cerveau rapide,
> Opus délégué, voix, écoute.

## Comportement attendu
- Les tours Opus nés de Jarvis (`via = JARVIS`) ajoutent leur coût à `jarvis_usage_days.opus_cost_usd` et
  `opus_turns` (migration 239), au moment où le tour est rangé.
- `GET /api/jarvis/costs` (compte courant) ⇒ `{ today: {fastBrain, opus, voice, listening, total}, month: {…} }` en
  euros (taux de la passerelle), arrondis au centime.
- Écran : ligne discrète à côté des réglages (« Jarvis : 0,42 € aujourd'hui · 6,10 € ce mois ») et détail par poste au
  survol ; relue à l'ouverture et après chaque échange (au plus toutes les 5 s). L'administrateur lit le même
  endpoint (pas de mesure admin séparée).

## Critères d'acceptation
- [ ] Un tour Opus via Jarvis augmente `opus` du jour ; un tour hors Jarvis non.
- [ ] Les montants sont en euros et filtrés `user_id`.

## Technique
Migration `239-jarvis-usage-opus.xml`, `JarvisUsageService.addOpus`, `AtelierChatService` (fin du tour via Jarvis),
`JarvisCostService` / `JarvisCostController`, écran (`costLine`, `euros`).

## Plan de test
Unitaire (agrégation jour / mois), intégration (endpoint, isolation), front (affichage).
