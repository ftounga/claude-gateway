# CADRAGE F-218 — Le mode offensif

**Statut :** brouillon du 2026-10-27, à compléter par l'agent de livraison (preuves fichier:ligne, découpage final)
**Demande PO :** 2026-10-27

## Le besoin, dans les mots du PO
« Depuis la feature chantier / plan d'action, je vais beaucoup plus lentement. Avant, je disais "déploie-moi tel truc" et tout se faisait automatiquement. Maintenant, sur chaque étape, on me demande une confirmation : l'agent rédige un commentaire et me demande de le valider, puis je dois encore valider le push ; ou "je fais un autoplan, valide-le avant le déploiement". Je veux un mode offensif : chaque action est conçue pour passer directement à done. Si je veux valider en amont, c'est à moi de l'écrire dans ma demande. »

## Mesures prod (7 jours au 2026-10-27)
- Chantiers : 13 ouverts (9 CAGIP, 4 EDENRED), 3 clôturés.
- Confirmations : 20 « Question posée » (`RunnerConfirmationGate`) en 48 h.
- Questions structurées : 35 appels `demander` en 7 jours (CAGIP 15, EDENRED 20).
- Porte du mode guidé : `GATE_BLOCKED` = 1. Ce n'est pas la cause.
- Événements : STEP_UPDATED 142, PLAN_VALIDATED 26, PLAN_AMENDED 14.

## Décision PO (2026-10-27) — révise F-167
**Irréversible : JAMAIS de validation.** MR/PR, apply/déploiement prod, envoi, suppression : tout va directement à done, chantier ou non.
- Si le PO veut valider avant, il l'écrit dans sa demande, et l'agent respecte cette consigne explicite.
- Cette décision **remplace** le garde-fou de F-167 (« sur l'irréversible on ne décide jamais seul → question structurée »).
- `demander` (F-164) reste réservé aux vrais arbitrages : information manquante, choix métier que seul le PO peut trancher. Il ne sert plus à « confirmer » une action.

L'alternative présentée au PO (une seule validation au départ d'un chantier, listant l'irréversible) a été écartée par lui.

## Périmètre
1. **Inventaire prouvé** (fichier:ligne) de tout ce qui provoque une confirmation d'action :
   - doctrines du prompt système : `DECIDE_BY_DEFAULT_DOCTRINE` (F-167), `ASK_QUESTION_DOCTRINE` (F-164) ;
   - consignes de chantier : `JourneyToolCatalog`, `JourneyTurnNote` ;
   - guides de gouvernance `savoir-durable` ;
   - étapes EXTERNE « confirmées » du tableau des chantiers (F-208) ;
   - porte `journeyGateRefusal` ;
   - `RunnerConfirmationGate`.

   Pour chacun, distinguer ce qui relève de la doctrine (choix de l'agent) et ce qui est imposé par le harnais.
2. **Doctrine offensive** : une action proposée est conçue pour aller jusqu'à done (commenter + pousser, plan + apply, MR + merge si c'est l'usage du dépôt). Littéral stable, sans rien de volatil dans le préfixe (cache F-134).
3. Les confirmations imposées par le harnais sur des actions sont retirées, ou alignées sur la décision.

## Garde-fous conservés
- Le réglage par sujet `agent_ask_before_bash` est un choix explicite de l'utilisateur ; il reste respecté.
- La validation du **plan** d'un chantier (un clic, F-176) n'est pas une confirmation d'action. Elle est gardée ; mesurer si elle ralentit.
- **Justesse avant coût (règle absolue)** : le mode offensif supprime des attentes, pas de la vérification. L'agent lit le plan terraform avant l'apply, lance les tests, vérifie le résultat.

## Hors périmètre
L'exécution autonome d'un chantier entier : c'est F-219.
