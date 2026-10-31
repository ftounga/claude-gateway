# CADRAGE F-224 — Le coût affiché = ce que je paie

**Statut :** cadrage du 2026-10-31 (issu des audits du 31/10), à compléter par l'agent de livraison (découpage en SF)

**Demande PO (2026-10-31)** : « Le budget affiché ne correspond pas à ce que je recharge sur ma carte (200 au moins 3 fois cette semaine). Le coût à l'écran doit correspondre exactement à ce que j'ai dépensé. »

## Mesure (audit du 31/10)
- **L'app** : 426 $ du 24 au 30/10 (+51 % sur une semaine ; la hausse vient de CAGIP, data-platform et agenor, avec des tours de livraison à plus de 1,5 M de tokens).
- **Le calcul** (`ProviderCostCalculator`, grille Opus 5.5 4/20/0,20/8 $ par M de tokens) est juste : `usage_turns` et `atelier_turn_measures` concordent.
- **L'écran** (`CostSummaryService`) montre la semaine depuis lundi 00:00 UTC, en euros à 0,92.
- **Non compté** :
  - les **tours coupés ou en échec** : l'enregistrement se fait en fin de tour, sans `finally` (`AtelierChatService` l.4363) ; 12 déploiements cette semaine et 34 messages sans réponse enregistrée ;
  - les appels annexes (aide, `ReasonedReader`, index de la carte), qui pèsent moins de 2 $ par semaine.
- **Hors app** (à dire au PO, pas à coder) :
  - l'usage supplémentaire Claude Max des sessions de développement, environ 359 $ au prix de l'API cette semaine ;
  - la clé legalCase (`…owAA`), si elle est dans la même organisation Anthropic.
- **Erreurs mineures** (surestimation) : l'exploration sur Sonnet est facturée au prix d'Opus, et Haiku 4.5 retombe sur le tarif de repli.

## Périmètre
1. **Tours coupés comptés** : coût et mesure enregistrés à chaque itération, ou en `finally`, y compris pour un tour interrompu, en échec ou tué par un déploiement (au mieux : ce qui a été facturé jusqu'à la coupure). Pas de double compte.
2. **Appels annexes comptés** (aide, lecture raisonnée, index de la carte), étiquetés « annexe ».
3. **Tarifs** : le modèle réellement appelé est facturé à son prix (Sonnet pour l'exploration, Haiku 4.5).
4. **Écran** : « 7 derniers jours » à côté de « semaine en cours », montant en **dollars** (la devise de la facture) avec l'euro et le taux affiché en second.
5. **Rapprochement avec la facture Anthropic** : une tâche quotidienne lit `GET /v1/organizations/cost_report` (et `usage_report` par clé API) avec une clé Admin (`APP_ANTHROPIC_ADMIN_KEY`, absente aujourd'hui). L'écran admin du coût montre « facturé par Anthropic » à côté de « calculé », avec l'écart, par jour et par clé. **Inactif tant que la clé n'est pas posée**, et l'écran dit comment la créer. Charger le skill claude-api pour l'API Admin.
6. Documenter dans `docs/ops/` comment séparer une clé par client (espace Anthropic par client) : décision PO, pas de code.

## Règles
Aucune baisse de raisonnement (justesse avant coût). Isolation `user_id`. La clé Admin n'est jamais journalisée.
