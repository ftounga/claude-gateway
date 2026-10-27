# CADRAGE F-219 — Exécuter un chantier en autonome

**Statut :** brouillon du 2026-10-27, à compléter par l'agent de livraison
**Demande PO :** 2026-10-27
**Dépend de :** F-218 (mode offensif), à livrer avant

## Le besoin, dans les mots du PO
« Une fois le plan fait, l'outil doit valider que tout est faisable : pas de point de blocage, rien à arbitrer. Si c'est le cas, je dois pouvoir dire "exécute-moi tel chantier en autonome" : il lance toutes les actions et part d'un trait. On devient beaucoup plus puissants : on pourra lancer plusieurs chantiers en même temps en autonome. »

## Périmètre
1. **Contrôle avant départ**, calculé par le serveur et non deviné. Il vérifie que :
   - le plan est validé ;
   - aucune étape n'est BLOQUÉE ;
   - aucune attente ouverte vers un tiers (F-175) ne conditionne une étape ;
   - aucune question n'est en suspens ;
   - aucune information manquante n'est marquée dans le plan ;
   - les dépendances `depends_on` sont satisfiables.

   Résultat : « Prêt à partir », ou la liste précise de ce qui empêche le départ.
2. **Lancement** : une commande (outil, bouton « Lancer en autonome » dans le tableau des chantiers F-208, commande slash si c'est simple) qui exécute toutes les étapes restantes d'un trait, sans question, jusqu'à la clôture proposée.
   - Sur un vrai blocage (échec non récupérable, information manquante découverte en route), l'agent s'arrête proprement et marque l'étape BLOQUÉE avec la raison.
   - Le PO est alors notifié par les notifications existantes (F-185 et F-217).
3. **Plusieurs chantiers en parallèle**, dans ces limites :
   - un chantier autonome par sujet à la fois (le focus F-206 reste) ;
   - plusieurs sujets en même temps, dans la limite des 4 terminaux vivants (F-70, refus explicite au-delà) ;
   - le tour vit côté serveur (F-84) : le PO peut quitter l'écran.
4. **Longueur des chantiers** : vérifier que le plafond d'étapes d'un tour (100) et la compaction tiennent un chantier entier. Sinon, enchaîner les tours : relance automatique tant que des étapes restent et qu'aucun blocage n'est apparu, de façon bornée et visible.
5. **Écran**, conforme à `DESIGN_SYSTEM.md`. Sur le tableau des chantiers :
   - le badge « Prêt à partir », ou la raison du refus ;
   - le bouton « Lancer en autonome » ;
   - l'état « En autonome — étape 3/7 » en direct ;
   - le bouton « Reprendre la main ».
6. **API** de préflight et de lancement, par chantier, avec isolation `user_id`. Elle est **réutilisée par F-220** (tableau par client, multi-sélection) et doit être documentée ici.
7. **Coût** : une estimation simple est affichée au lancement si les mesures existent (`atelier_turn_measures`). Pas de confirmation demandée (décision F-218).

## Règles
- Justesse avant coût : l'agent autonome vérifie chaque étape (preuve dans les sous-étapes, F-208), il ne coche pas à l'aveugle.
- Rien de volatil dans le préfixe stable.
