# Cadrage — F-168 « Vérifier avant de conclure »

> Cadrage PO le 2026-09-30. Née de l'**audit du 2026-09-30 sur conversations réelles**.
> Source de vérité produit : `docs/PROJECT.md`. Règle absolue PO : **justesse avant coût**.

---

## Pourquoi (audit du 2026-09-30 sur conversations réelles)

Au **premier passage** sur un nouveau sujet, l'agent de l'Atelier **SUPPOSE au lieu de VÉRIFIER**
et **DÉLÈGUE à l'humain avant d'épuiser ses moyens locaux**. Cas réels observés :

- a annoncé « **MR fermée** » alors qu'elle était **ouverte** (jamais vérifié via l'API) ;
- « plus appliqué depuis oct. 2025 » = **déduction** présentée comme un fait, **corrigée** en lisant
  le **state S3** — ce qui a **renversé une recommandation dangereuse** ;
- a préparé **14 questions** à des humains dont **5 répondables** depuis la **carte d'infra** qu'il
  avait déjà.

**Cause racine** : **PAS DE CRITÈRE D'ARRÊT FONDÉ SUR LA PREUVE**. « Répondre » n'exige pas « avoir
prouvé ou nommé le blocage précis » — donc **la supposition est le point d'équilibre le moins
coûteux**. Aggravé par le **plafond de consommation** qui **tronque** une investigation en une
conclusion « qui a l'air finie ».

---

## Intention (décidée par le PO le 2026-09-30)

Doter le terminal d'une **doctrine « vérifier avant de conclure »** qui installe un **critère
d'arrêt fondé sur la preuve** : une affirmation d'**état factuel** doit être **prouvée** par une
vérification live, ou le **blocage précis** doit être **nommé** — la supposition muette n'est plus
un point d'arrêt acceptable.

C'est le **pendant investigation/preuve** des doctrines déjà livrées sur l'axe conduite :

- **F-164** (`ASK_QUESTION_DOCTRINE`) = *quand / comment demander* ;
- **F-167** (`DECIDE_BY_DEFAULT_DOCTRINE`) = *quand ne pas demander* (décider par défaut) ;
- **F-168** (`VERIFY_BEFORE_CONCLUDE_DOCTRINE`) = *ne pas supposer ni déléguer sans avoir vérifié*.

---

## La doctrine — les 5 points

1. **Trancher tout ÉTAT FACTUEL par une VÉRIFICATION LIVE** (lecture fichier, commande, API, state,
   logs, carte d'infra) — **jamais** par une note (`STATE.md`), un doc ou une **déduction présentée
   comme un fait**.
2. **Distinguer explicitement « mesuré » vs « supposé »** dans la réponse (**obligatoire**).
3. **PORTE AVANT DÉLÉGATION** : avant d'écrire « à confirmer / non vérifié / je recommande de
   demander à X », se demander « **puis-je répondre depuis le repo / cluster / state / logs / la
   carte ?** ». Ne **déléguer que si NON**, en **NOMMANT le blocage précis** (droit refusé, auth
   humaine non scriptable, incident) — sur le modèle des **reports légitimes déjà réussis**.
4. **FALSIFIER sa 1ère hypothèse** (contre-test sur un autre cas / une autre fenêtre) **avant de
   conclure** (cohérent avec l'acquis « première cause ≠ seule cause »).
5. **MARQUAGE INCOMPLET** : ne **jamais** présenter une investigation interrompue / partielle comme
   finie ; **étiqueter « INCOMPLET — vérifications restantes : … »**, en particulier quand un tour
   **touche le plafond** de consommation.

---

## Garde-fou CRITIQUE — ne pas casser la frugalité

Subordonné à la **RÈGLE ABSOLUE PO** *justesse avant coût*, **mais aussi** à l'acquis « lecture
raisonnée à la demande » (F-157) : l'exhaustivité **OBLIGATOIRE** porte **uniquement** sur

- les **AFFIRMATIONS D'ÉTAT FACTUEL**, et
- la **VÉRIFICATION AVANT DÉLÉGATION**,

**PAS** sur une exploration systématique de tout. La doctrine **ne doit pas transformer chaque tour
en exploration exhaustive**. On vérifie **ce qu'on affirme** et **ce qu'on s'apprêtait à déléguer**,
pas l'univers.

---

## Placement & implémentation

- **Doctrine produit dans le prompt système** (`AtelierChatService.buildSystemPrompt`), sur le
  modèle **exact** des doctrines livrées : `ASK_QUESTION_DOCTRINE` (F-164),
  `DECIDE_BY_DEFAULT_DOCTRINE` (F-167), `DURABLE_KNOWLEDGE_DOCTRINE` (F-166).
- Nouveau **littéral stable** `VERIFY_BEFORE_CONCLUDE_DOCTRINE`, injecté dans `buildSystemPrompt`.
- **Additif** + **littéral stable** → cache de prompt (F-134) préservé.
- **Réutilise** les outils existants (`read_file`, `bash`, `run_command`, appels d'API via les
  outils déjà présents) : la doctrine les **référence**, elle n'ajoute **aucun outil**.
- **Aucun nouvel outil, aucune table, aucun endpoint, aucune migration.**

### Scope d'injection retenu : **universel** (toutes les cibles)

Comme F-167 (`ASK_QUESTION_DOCTRINE` / `DECIDE_BY_DEFAULT_DOCTRINE`), F-168 est injectée **sur
toutes les cibles**, **sans condition**. Justification :

1. **Elle régit toute affirmation d'état factuel**, pas une cible précise. Un « MR fermée », un
   « plus appliqué depuis… », un « le fichier contient X » peuvent être affirmés depuis **n'importe
   quelle** cible (SANDBOX hébergé, terminal du poste, terminaux de sujet, Teams).
2. **La porte avant délégation vaut partout** : sur toute cible, l'agent peut écrire « je
   recommande de demander à X » sans avoir épuisé ses moyens locaux.
3. **Cohérence de l'axe** : F-168 prolonge l'axe *demander ↔ décider ↔ prouver* dont F-164 et F-167
   sont universelles ; scinder par cible rendrait l'axe incohérent.
4. **Ce n'est pas lié à la présence d'un dépôt réel** (la raison du scope host+sujet de F-166) : la
   preuve peut être une lecture de fichier, une commande, une API, un state, des logs ou la carte.

---

## Option étudiée — message « plafond de consommation »

Le cadrage a étudié un **lissage minimal** du message `SPEND_CAP_REPLY` (« Ce message a atteint son
plafond de consommation… », `AtelierChatService`) pour **cadrer l'INCOMPLET**.

**Décision (par défaut, flaguée) : NE PAS y toucher.** `SPEND_CAP_REPLY` est émis pour **TOUS** les
tours coupés au plafond, **pas seulement** les tours d'investigation. Le lisser pour évoquer
l'INCOMPLET exigerait soit (a) de **détecter « c'est un tour d'investigation »** — **fragile**, et
explicitement **déconseillé** par le cadrage —, soit (b) d'**imposer un cadre d'investigation à tout
tour coupé** (bruit, et faux sur un tour non-investigation). L'**INCOMPLET est porté par la doctrine
(point 5)** uniquement : l'agent **s'auto-étiquette** avant que le plafond ne tombe. Choix **propre,
additif, byte-stable** (cache F-134 préservé), aucun risque sur la frugalité.

---

## Découpage

- **SF-168-01** — la doctrine : littéral `VERIFY_BEFORE_CONCLUDE_DOCTRINE` + injection universelle +
  tests (présence sur les deux cibles, les 5 points présents, garde-fou frugalité présent,
  byte-stable). **Clôt F-168.**

*(Pas de subfeature de détection automatique de « tour d'investigation » ni de compteur de preuves :
le tri mesuré/supposé et la porte avant délégation sont un **raisonnement du modèle guidé par la
doctrine**, pas un classifieur en code — cf. hors périmètre.)*

---

## Hors périmètre

- Pas de **détection fragile de « type de tour »** ni de **compteur de vérifications** en code.
- Pas de **lissage du message plafond** (décision ci-dessus : porté par la doctrine, point 5).
- Pas d'**exploration systématique imposée** : l'exhaustivité porte sur l'affirmation d'état et la
  porte avant délégation, pas sur tout (garde-fou frugalité).
- Pas de **nouvel outil** : réutilise les outils de lecture / exécution existants.
- Pas de table, endpoint, migration, ni changement d'UI.

---

## Conformité

- **Gateway-First / Provider-First** respectés (doctrine prompt-only, aucun moteur maison).
- Isolation `user_id` inchangée.
- Aucune incohérence avec `ARCHITECTURE_CANONIQUE.md` (aucune table).
- Règle absolue PO **justesse avant coût** portée : la doctrine **augmente** la justesse (on prouve
  au lieu de supposer) sans imposer de coût d'exploration systématique (garde-fou frugalité).
