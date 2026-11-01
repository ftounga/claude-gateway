# CADRAGE F-230 — Les veilles Jarvis

**Statut :** cadrage du 2026-11-01 · Famille « Jarvis » : F-230 (veilles), F-231 (mode Jarvis), F-232 (compagnon Android).

**Demande PO (2026-11-01)** : « un chef d'orchestre de mes chantiers et clients, avec la voix ». Jarvis attend qu'on l'interpelle, sauf sur ce que le PO a configuré exprès : « mets-moi un suivi Jarvis sur ce chantier, via nos tickets Jira de tel tableau ». Exemples :
- tickets d'alarme chez EDENRED ;
- tableau agenor chez CAGIP, une fois par jour, tickets « en cours », « bloqués », « ready to dev » et nouvellement assignés au PO, jamais les « done ».

Une veille ne tourne **que du lundi au vendredi**.

## Constat (existant et usage mesuré)

- **Routines F-183** : déclaration par sujet, rythme `WEEKDAYS`, worker, poste requis, budget, notification. **En prod au 2026-11-01 : 0 routine configurée, 0 exécution.** Le besoin existe, mais le mécanisme actuel n'est pas adopté. De plus, chaque exécution est un **tour d'agent complet** (≈ 0,50 €), trop cher pour vérifier plusieurs fois par jour que rien n'a bougé.
- **Notifications qui sonnent** (F-153, F-217), **suivi par chantier** (F-218 à F-221), **registre des accès sans secret** (F-180).
- L'accès aux plateformes (Jira, Monday, ClickUp…) se fait **depuis le poste du client**, avec ses jetons et à travers son réseau. La gateway n'a pas d'accès direct et n'en aura pas.

## Décisions

| # | Décision | Pourquoi |
|---|---|---|
| D1 | **Une veille = sujet (+ chantier facultatif), plateforme et périmètre en clair, sonde, jours (lundi→vendredi par défaut et imposé), heures données par le PO, active.** Elle est posée **par le PO uniquement** : à la voix, à l'écrit (« mets-moi un suivi Jarvis… ») ou sur une proposition de Jarvis à l'ouverture d'un chantier, validée par le PO. | Rien d'automatique : exigence PO |
| D2 | **La sonde est écrite une fois par l'agent, puis tourne sans IA.** À la création, l'agent écrit sur le poste une commande en lecture seule (appel REST/GraphQL de la plateforme) qui rend une liste normalisée `{id, titre, statut, assigné, mis_à_jour, dernier_commentaire}`. Il l'exécute une fois et montre le résultat au PO (« je vois 12 tickets : 5 en cours, 2 bloqués… ») ; le PO valide. | « N'importe quoi » devient possible sans payer l'IA à chaque passage |
| D3 | **Détection d'abord.** À chaque passage, la gateway lance la sonde par le runner (commande, **pas** un tour d'agent), compare avec l'instantané précédent (empreinte par élément) et ne fait rien si rien n'a changé. | Coût d'un passage ≈ 0 |
| D4 | **Sur changement seulement**, un appel IA léger (par `AIProvider`), qui ne reçoit **que le diff** et le périmètre, rédige une phrase (« Agenor : 2 tickets passés en bloqué, 1 nouveau assigné à toi »). Notification qui sonne ; un toucher ouvre Jarvis (F-231), qui la lit. La ligne est aussi posée dans le fil du chantier. | Justesse : l'IA lit le changement réel, pas un résumé de résumé |
| D5 | **Sécurité de la sonde** : lecture seule (méthodes GET, ou requête GraphQL sans mutation), jamais de secret en clair dans la commande (lecture d'un fichier ou d'une variable du poste ; refus à l'enregistrement d'une commande qui contient un littéral ressemblant à un jeton). Commande visible et validée par le PO. | Une commande qui tourne sans humain |
| D6 | **Pannes** : poste hors ligne → passage « manqué » et rattrapage unique à la reconnexion. Sonde en échec 2 fois de suite (jeton expiré…) → notification « veille en panne », proposition de réparation par l'agent. | Une veille muette ne doit pas passer pour « rien de neuf » |
| D7 | **Réutiliser le planificateur des routines** (cadence, poste requis, rattrapage, garde de déploiement), mais avec une table et une exécution propres : une veille n'est pas une routine. | Ne pas dupliquer le moteur |
| D8 | **Mesure d'adoption** : veilles posées, passages, changements, alertes ouvertes. Revue à J+7 et J+14. | Leçon « mécanisme livré qui dort » |

## Coût estimé

- Passage sans changement : ≈ 0 (une commande sur le poste).
- Changement : un appel limité au diff, de l'ordre de 0,05 à 0,10 €.
- 10 veilles et ~3 changements par jour ouvré : **≈ 30 à 65 €/mois**.
- Se limiter du lundi au vendredi réduit **peu** le coût, puisque les passages sont gratuits. Les changements du week-end sont analysés en un seul diff le lundi : un appel au lieu de plusieurs. Le gain principal est le calme du week-end.

## Découpage

| SF | Objet | Estim. |
|---|---|---|
| SF-230-01 | **Poser une veille.** Table `jarvis_watches`, API, outil `veille_poser` (sonde écrite et testée, carte de validation avec le premier résultat), commande `/veilles`. Le PO dit la fréquence ; jours lundi→vendredi. | 2 j |
| SF-230-02 | **Elle tourne sans IA.** Passage planifié par le runner, instantané `jarvis_watch_snapshots`, diff, manqué/rattrapage, « veille en panne ». | 2 j |
| SF-230-03 | **Elle t'interpelle.** Appel IA sur le diff seul, notification qui sonne avec lien vers Jarvis, ligne dans le fil du chantier, budget et coût en €. | 1,5 j |
| SF-230-04 | **Les voir et les tenir.** Liste par sujet et dans le tableau du client (dernier/prochain passage, état), pause/reprise/suppression ; Jarvis propose une veille à l'ouverture d'un chantier ; compteurs d'adoption (D8). | 1,5 j |

## Préoccupations transversales

- **Plans / limites** : consommation hors présence humaine (D4). Composants : `QuotaWindowService`, `CostBudget`, `UsageLedgerService`, garde de quota du tour, plafond mensuel par veille.
- **Contexte tenant** : veille liée à `user_id` + `workspace_id` (+ chantier), vérifiée à chaque passage ; instantanés filtrés pareil.
- **Navigation** : lien profond de la notification vers `/jarvis` (route créée par F-231 ; avant F-231, il mène au fil du chantier).

## Hors périmètre

- Écrire sur une plateforme (commenter, changer un statut) depuis une veille.
- Veilles le week-end.
- Accès direct de la gateway aux plateformes.
- La voix (F-231).
