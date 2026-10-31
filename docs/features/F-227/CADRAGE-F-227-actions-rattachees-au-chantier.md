# CADRAGE F-227 — Chaque action rangée sous son chantier

**Statut :** cadrage du 2026-10-31 (issu des audits du 31/10), à compléter par l'agent de livraison (découpage en SF)

**Question PO (2026-10-31)** : « Faut-il que toutes les actions aillent dans un chantier, par exemple un chantier par défaut ? »

## Mesure et arbitrage (audit du 31/10)
64 actions ouvertes (EDENRED 37, CAGIP 27), dont 6 seulement (9 %) sont rattachées à un chantier (une étape `waitsOn` = `dedup_key`). Les 58 autres :
- **32** viennent de sujets qui n'ont jamais eu de chantier (alarm4tech, cloudops-run, gitlab-tfstate, pentest-opera, spider-mfa, Terminal du poste). C'est normal, ce sont des sujets en mode Libre.
- **25** sont nées pendant un chantier ouvert (agenor, waf, secrets-azure-devops, data-platform, chaine-marchand), mais sans lien : **c'est le vrai défaut**.
- 1 cas isolé.
- Des attentes sont périmées depuis le 30/09.

**Décision : pas de chantier fictif par défaut.** Il casserait le préflight F-219, le fil par chantier F-221, le worktree et les compteurs « dormant ». À la place :

## Périmètre
1. `terminal_actions.chantier_number` (nullable, migration avec retour arrière), posé automatiquement quand l'attente naît dans le fil d'un chantier (même source que le message F-221). Le tableau du client et le tableau des chantiers rangent l'attente sous son chantier, même sans `waitsOn`. Reprise des 25 existantes par fenêtre temporelle (ouverture et clôture du chantier, sujet).
2. La section « Hors chantier » du tableau du client devient **« Au fil de l'eau »**, groupée par sujet (libellé seulement).
3. Gestes : **[Rattacher à un chantier]** sur une attente, et **[Revoir les attentes de plus de 14 jours]** (proposition de clôture en lot, sur la clôture proposée de F-175 qui existe déjà).

Isolation `user_id`. Le Radar n'est pas touché.
