# CADRAGE F-222 — Le profil offensif — plus d'attente fabriquée

**Statut :** cadrage du 2026-10-31 (issu des audits du 31/10), à compléter par l'agent de livraison (découpage en SF)

**Demande PO (2026-10-31)** : « Je déploie beaucoup plus lentement, je dois intervenir plusieurs fois. Quand je coche une tâche, il la lance jusqu'au bout. Je veux un profil offensif, straightforward, pour mon compte admin. » Prolonge F-218 (l'irréversible n'est jamais confirmé).

## Mesure (audit du 31/10, base prod, 61 h après F-218)
- Environ 29 interventions pour débloquer en 2,5 jours actifs, soit 19 % des messages du PO (13 évitables et 16 clics de plan ou de diagnostic).
- 3,5 amendements par jour (le chantier 6 d'agenor est passé de v2 à v4 en 27 min ; le chantier 9 de data-platform de v2 à v5 en 40 h), dont la moitié sont de petits ajouts.
- Le harnais n'est presque pas en cause : `GATE_BLOCKED` = 2, `agent_ask_before_bash` = false partout, toutes les règles en ALLOW, `RunnerConfirmationGate` muet en mode offensif.

## Causes prouvées
1. **Chaque amendement exige un clic, à cause du prompt** : `JourneyToolCatalog.java:105-110` (« il repasse par la validation »), `JourneyTurnNote.java:168-170` (« ne modifie rien avant sa validation »), `JourneyGate.java:47-54` (« Attends son clic »). La porte, elle, ne compare pas l'action au plan (`JourneyGate.isClosed`, l.65-86).
2. **Un « go » écrit ne valide pas un plan** : aucun outil ne le permet. `confirmer_diagnostic` (SF-176-12) existe pour le diagnostic seulement.
3. **L'agent inscrit lui-même des points d'accord**, que `AtelierChatService.java:629-632` accepte ensuite comme « règles du projet » :
   - dans les plans (« apply sur ton accord écrit », « pas d'action sans son go ») ;
   - dans les prompts qu'il rédige pour le PO (« attends ma validation », « MR sans fusion ») ;
   - dans la gouvernance (alarm4tech : « toute écriture Jira exige l'accord préalable », jamais demandé par le PO).
4. **La lecture attend** (« dis continue pour que je lance les relevés »). `JourneyRiskClassifier.java:360-362` classe `python`, `node`, `mvn` et `gradle` comme modifications : un script de lecture du poste (`jira.py voir`) est donc bloqué hors Exécution.
5. **F-221 contredit F-218** : `AtelierChatService.java:2228` contient « ne fusionne rien sans accord ».
6. **Bug** : la validation du plan v2 du chantier 4 de data-platform (29/10 16:14, « J'ai validé le plan v2 ») n'a laissé aucun `PLAN_VALIDATED`. Cause probable : le même défaut hors focus que celui que SF-176-12 a corrigé pour le diagnostic (le geste vise le chantier au focus).
7. **Chantiers zombies** : data-platform 3, 6 et 7 sont en Exécution depuis le 21-25/10. Ils faussent la porte agrégée et alourdissent la note de chaque tour. Il faut les rendre visibles et proposer leur clôture ; ne jamais les clore en silence.
8. **Envois en double** : 5 messages du PO dupliqués (jusqu'à trois fois le même). Diagnostiquer la cause : double clic, ou renvoi après coupure.

## Périmètre
- Un **amendement dans le même objectif** (le chantier ne change pas de but) s'applique **sans clic** : il est journalisé (événement), annoncé dans la réponse, et le plan reste « validé ». Seul le **premier plan** d'un chantier garde le clic (F-176). Un changement d'objectif reste un amendement à valider. Le critère, en texte stable, va dans le guide.
- Outil **`valider_plan`** : un « go » écrit valide le plan, avec `chantier` explicite et la citation `user_words` exigée (modèle : `confirmer_diagnostic`).
- **Doctrine** : l'agent n'inscrit jamais un accord de l'utilisateur (dans un plan, une suite, un prompt rédigé ou une règle de gouvernance) que celui-ci n'a pas écrit lui-même. L'accord d'un tiers est une attente (`record_blocker`), pas un accord du PO. La lecture n'attend jamais. Tout en littéral stable : rien de volatil dans le préfixe (cache F-134).
- **Liste blanche de scripts de lecture** du poste, classés LECTURE (par exemple `* voir`, `releve*`, `--dry-run`). Conservatrice : en cas de doute, on garde la classification actuelle.
- F-221 : « fusionne quand relecteur-mr et les tests sont verts, sauf consigne contraire ».
- Corriger le clic de validation perdu (geste par numéro de chantier, comme SF-176-12).
- Chantiers zombies : signaler « en Exécution sans activité depuis N jours » (tableau des chantiers et tableau du client) avec [Clore] ; la note de tour ne liste plus que les chantiers actifs.
- **Hors périmètre** : retirer la règle alarm4tech. C'est une donnée de gouvernance du PO ; elle est signalée dans le récap.

## Règles
- **Justesse avant coût** : on supprime des attentes, jamais de la vérification (plan terraform lu avant apply, tests, relecture).
- `agent_ask_before_bash` reste respecté. La consigne explicite du PO (« montre-moi avant ») reste respectée.
