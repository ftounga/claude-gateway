# Cadrage — F-167 « Décider par défaut et avancer »

> Cadrage PO le 2026-09-30. Complément de F-164 (questions structurées).
> Source de vérité produit : `docs/PROJECT.md`. Règle absolue PO : **justesse avant coût**.

---

## Intention (décidée par le PO le 2026-09-30)

Doter le terminal d'une **doctrine « décider par défaut et avancer »** : sur un choix à
**FAIBLE ENJEU / RÉVERSIBLE**, l'agent **choisit un défaut raisonnable**, l'**ANNONCE**
(« je pars sur X, dis-moi si tu veux ajuster ») et **CONTINUE**, au lieu de s'arrêter pour
demander.

C'est le **complément** de F-164 (questions structurées) :

- **F-164** = *quand* et *comment* demander (outil `demander`, questions à réponses proposables) ;
- **F-167** = *quand ne pas demander* — le petit choix local, sans regret, où un aller-retour
  ne sert qu'à figer le tour et à payer un tour de plus.

Le besoin est réel : un tour qui s'arrête sur « où je range ce fichier de travail ? » ou
« quel nom pour cette variable ? » coûte un aller-retour humain pour un choix sans conséquence.
La doctrine apprend à l'agent à trancher ces choix-là et à avancer, tout en gardant l'utilisateur
informé (annonce) pour qu'il puisse corriger à chaud.

---

## Garde-fou — CŒUR de la feature

Subordonné à la **RÈGLE ABSOLUE PO** : *justesse avant coût — ne jamais dégrader la justesse
des résultats*.

Sur l'**IRRÉVERSIBLE** ou le **SENSIBLE**, on ne décide **JAMAIS** seul → on passe par une
**QUESTION STRUCTURÉE** (F-164, outil `demander`). Décider-par-défaut y est **INTERDIT**.

Catégories irréversibles / sensibles énumérées dans la doctrine :

- ouvrir une **MR / PR** ;
- **apply / déploiement** en prod ;
- **suppression** de données ou de fichiers ;
- **envoi externe** (e-mail, publication) ;
- **dépense d'argent** ;
- toute **opération destructive** ;
- **changement de sécurité / permissions**.

**En cas de doute** sur le caractère réversible : traiter le choix comme **irréversible** et
demander. Le coût d'une question de trop est faible ; le coût d'une action irréversible mal
devinée ne l'est pas.

---

## Placement & implémentation

- **Doctrine produit dans le prompt système** (`AtelierChatService.buildSystemPrompt`), sur le
  modèle des doctrines déjà livrées : `ASK_QUESTION_DOCTRINE` (F-164),
  `SUBJECT_ROUTING_DOCTRINE` / `SUBJECT_HANDOFF_DOCTRINE` (F-141),
  `DURABLE_KNOWLEDGE_DOCTRINE` (F-166).
- Nouveau **littéral stable** `DECIDE_BY_DEFAULT_DOCTRINE`, injecté dans `buildSystemPrompt`.
- **Additif** + **littéral stable** → cache de prompt (F-134) préservé.
- **Réutilise F-164 / `demander`** pour l'escalade : la doctrine la **référence** dans son texte,
  elle ne la réimplémente pas.
- **Aucun nouvel outil, aucune table, aucun endpoint, aucune migration.**

### Scope d'injection retenu : **universel** (les deux cibles)

Contrairement à F-166 (`isRunnerTarget()`, host + sujet), F-167 est injectée **sur les deux
cibles**, **sans condition**, exactement comme `ASK_QUESTION_DOCTRINE` (F-164). Justification :

1. **Complément d'une doctrine universelle.** F-167 est explicitement le complément de F-164, dont
   `ASK_QUESTION_DOCTRINE` est injectée **universellement** (sur les deux cibles, sans condition).
   Scinder les deux moitiés d'un même axe *demander ↔ ne pas demander* selon la cible les rendrait
   incohérentes.
2. **Outil `demander` universel.** L'escalade repose sur `demander` (F-164), disponible sur les
   deux cibles ; la doctrine qui aiguille vers lui doit valoir partout où il existe.
3. **Le choix à trancher est agnostique de la cible.** Un petit choix réversible surgit sur
   n'importe quelle cible (SANDBOX hébergé, terminal du poste, terminaux de sujet) — ce n'est pas
   lié à la présence d'un dépôt réel (la raison du scope host+sujet de F-166).
4. **Le garde-fou vaut partout.** Ouvrir une MR/PR, déployer, supprimer, envoyer, dépenser sont
   sensibles quelle que soit la cible.

---

## Découpage

- **SF-167-01** — la doctrine : littéral `DECIDE_BY_DEFAULT_DOCTRINE` + injection universelle +
  tests (présence sur les deux cibles, garde-fou irréversible présent, byte-stable).

*(Pas de subfeature de classification automatique : le tri réversible/irréversible est un
raisonnement du modèle guidé par la doctrine, pas un classifieur en code — cf. hors périmètre.)*

---

## Hors périmètre

- Pas de **classifieur automatique** réversible/irréversible en code (raisonnement du modèle,
  guidé par la doctrine).
- Pas de **changement d'outils** (réutilise `demander` de F-164, aucun nouvel outil).
- Pas de table / endpoint / migration.

---

## Conformité

- **Gateway-First / Provider-First** respectés (doctrine prompt-only, aucun moteur maison).
- Isolation `user_id` inchangée.
- Aucune incohérence avec `ARCHITECTURE_CANONIQUE.md`.
- Règle absolue PO **justesse avant coût** portée par le garde-fou (cœur de la feature).
