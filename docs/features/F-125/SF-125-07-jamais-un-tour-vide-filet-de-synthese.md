# SF-125-07 — Jamais un tour vide : filet de synthèse en fin de tour

> Cadrage du 2026-09-18 (PO), après un cas réel CAGIP **observé et tracé en production**.
> **Cadrage seul : livraison sur go.** Ceinture de sécurité **complémentaire** de SF-125-06.

## 1. Le constat (cas réel, terminal racine CAGIP, tracé en base)
Le PO colle à l'agent la **sortie** d'une commande que l'agent lui avait demandé d'exécuter
(`saml2aws list-roles … ; echo "code=$?"` → `code=0`, **liste de rôles vide**). Ce collage est un
**résultat à interpréter**, pas un ordre.

Trace `runner_audit` (2026-09-18) :
- `07:58:43` bootstrap ; `07:59:04`→`07:59:57` **5 `bash`** (l'agent **re-teste lui-même** la config :
  `/tmp/s2a-test.conf`, URLs `127.0.0.1:1`, lecture de `list_roles.go`…) ; `08:00:21` un `python` qui
  **édite `acces.md`** (il « range » dans la carte) ; `08:00:41` `screen_list_files`.
- Log backend `08:00:35` : `AtelierCheckpointRunner : Point de contrôle BLOQUANT (point=END_OF_TURN,
  contrôle=GovernanceEndOfTurnCheckpoint)`.
- Message assistant stocké `08:00:41` : **vide** → placeholder « Je n'ai pas produit de réponse pour ce
  message. » **Reproduit** à `08:21:29` sur la relance « refais l'analyse » (même workspace, même
  checkpoint bloquant).

**Observation directe du PO (décisive)** : *« Pendant le déroulement j'ai vu l'essentiel de ma réponse,
j'ai même le détail — le même diagnostic que toi sur l'URL dupliquée — mais à la fin tout ça disparaît
pour laisser place [au placeholder]. »*
→ **La bonne réponse A ÉTÉ PRODUITE et streamée** (essentiel + détail, diagnostic juste). Puis le tour
**a continué** (promotion `acces.md` à `08:00:21`, `END_OF_TURN` bloquant à `08:00:35`) et le **dernier**
message du tour (vide) a **écrasé** la réponse que l'utilisateur avait déjà sous les yeux.

**Le vrai défaut n'est donc pas « il n'a pas répondu » — c'est « il a répondu, puis sa propre plomberie a
jeté la réponse ».** Deux défauts cumulés :
1. **La réponse produite est détruite** : le tour ne s'arrête pas à la réponse ; il enchaîne de la
   plomberie (promotion/carte + `END_OF_TURN` bloquant), et le message final **vide** remplace, à
   l'affichage comme en base, la réponse déjà streamée. **C'est le pire résultat possible** : l'utilisateur
   a vu la réponse puis on la lui retire.
2. **F-120 glisse** : un **résultat collé** est traité comme un **ordre d'agir** (6 `bash` d'auto-test) au
   lieu d'être seulement **lu puis expliqué** — ce qui allonge le tour et multiplie les occasions
   d'enchaîner sur la plomberie fatale.

## 2. Rapport à SF-125-06 (ne pas dédoubler)
- **SF-125-06** retire la **cause racine** : le rituel promotion/dette imposé au modèle et le caractère
  **bloquant** de `GovernanceEndOfTurnCheckpoint`. Une fois livré, le tour n'est **plus mangé** par la
  comptabilité → cette famille de tours vides disparaît à la source.
- **SF-125-07** est la **ceinture** : même sans rituel, un modèle peut **finir sur un appel d'outil** (ou
  sur un texte réduit à néant par le strip `fin-de-tour` de SF-125-01). Le **harness** ne doit **jamais**
  livrer un tour vide. Indépendant de la gouvernance : c'est une garantie du **cycle de vie du tour**.

**Ordre** : SF-125-06 d'abord (racine) → SF-125-07 (filet). SF-125-07 a de la valeur **même seule**.

## 3. Objectif (une phrase)
**La réponse que l'utilisateur a vue ne doit jamais lui être retirée** : le texte destiné à l'utilisateur
produit pendant le tour est **conservé** comme réponse du tour ; aucune itération de plomberie
postérieure (promotion/carte, `END_OF_TURN`) ne peut le **remplacer** par un message vide, et si vraiment
rien n'a été produit, le serveur **provoque une synthèse** avant tout placeholder.

## 4. Comportement attendu
1. **Conserver le texte déjà produit (cœur)** : le serveur **mémorise, au fil du tour, le dernier texte
   destiné à l'utilisateur** que le modèle a émis. À la clôture, **ce texte est la réponse du tour** —
   même si des itérations ultérieures (édition de la carte, `END_OF_TURN`) n'ont produit aucun texte. Une
   itération de plomberie **ne peut jamais** écraser une réponse déjà streamée par un message vide.
2. **Détection « vraiment rien »** : réponse absente uniquement si **aucun** texte utilisateur n'a été
   émis de tout le tour (tous les blocs étaient `tool_use`, ou tout le texte est **vide après strip** des
   marqueurs `fin-de-tour`, hors bloc `<<essentiel>>`).
3. **Filet — une passe de synthèse forcée** : dans ce seul cas, le serveur relance **une fois** le modèle :
   *« Le tour s'achève. Réponds maintenant, directement, à la dernière demande de l'utilisateur, en
   t'appuyant sur ce que tu viens de faire/observer. Pas de plomberie. »* La réponse devient celle du tour
   (balisage `<<essentiel>>` normal — F-126).
4. **Dernier recours honnête** : si la synthèse ne rend toujours rien (erreur API, budget épuisé), afficher
   un message **explicite et actionnable** — p. ex. *« Je me suis arrêté après plusieurs actions sans
   conclure. Redemande-moi la synthèse. »* — **jamais** « Je n'ai pas produit de réponse pour ce message. »
5. **Borne anti-boucle** : la passe de synthèse est **unique**, ne redéclenche **aucun** crochet bloquant,
   et est plafonnée en tokens/temps.

## 5. Cas d'erreur / limites
- **Tour réellement tué** (pod recyclé, écran quitté — cf. mémoire « le tour vit dans le flux ») : hors
  périmètre ici ; ce n'est pas un tour « vide » mais un tour **interrompu** — traité ailleurs. La détection
  §4.1 ne se déclenche qu'à une **clôture normale** du tour.
- **Tour volontairement sans texte** (ex. action pure validée, si un tel cas existe) : la synthèse dit au
  moins **ce qui a été fait** (« fait : X »), jamais rien.

## 6. Portée & implémentation (esquisse — à préciser en mini-spec)
- **`AtelierChatService.runLoop`** : **mémoriser au fil des itérations le dernier texte destiné à
  l'utilisateur** (non vide après strip). À la clôture (après `END_OF_TURN`) : si ce texte existe → c'est
  la réponse du tour (ne pas laisser une itération vide postérieure le remplacer) ; sinon → passe de
  synthèse (une fois) ; sinon → dernier-recours.
- **Rendu / persistance** : la valeur écrite dans `atelier_messages` est ce **texte conservé** (ou la
  synthèse / le dernier-recours) ; ne plus jamais **écrire le placeholder** « Je n'ai pas produit de
  réponse ». Vérifier que le SSE et la persistance convergent sur la **même** valeur finale (le bug vient
  de ce que l'affichage streamé et le message final divergent).
- **Interaction strip (SF-125-01)** : la détection « vide après strip » réutilise la logique de strip
  existante ; ne pas re-stripper le bloc `<<essentiel>>` (F-126).
- **Aucune table, aucune migration, aucun endpoint, aucun composant frontend nouveau.**

## 7. Critères d'acceptation (vérifiables)
- **Scénario du cas réel** : un tour qui émet un texte utilisateur **puis** enchaîne des itérations
  d'outils sans texte (édition carte + `END_OF_TURN`) **conserve le texte émis** comme réponse du tour —
  test d'intégration reproduisant « réponse streamée, puis plomberie, puis message final vide » ; la
  réponse finale = le texte streamé, **pas** le vide.
- Un tour dont **aucun** bloc n'a de texte (tous `tool_use`) **produit tout de même** une réponse via la
  passe de synthèse.
- Un tour dont le texte est **vide après strip** déclenche la synthèse.
- Le placeholder littéral **« Je n'ai pas produit de réponse pour ce message. »** n'est **plus jamais**
  persisté ni affiché (substring absent du code de rendu ; remplacé par le texte conservé, la synthèse, ou
  le message de dernier recours).
- La passe de synthèse est **unique** (pas de boucle), ne relance **aucun** checkpoint bloquant.
- Non-régression : F-125-01 (strip), F-126 (`<<essentiel>>`), F-119/F-120 intacts.

## 8. Hors périmètre
- La **cause racine** du rituel (SF-125-06) : traitée là-bas.
- Le **tour tué** par recyclage de pod / sortie d'écran (mémoire dédiée) : distinct.
- La **discipline F-120** (résultat collé ≠ ordre) : renforcée séparément si le cas se répète après 125-06
  (ici, on garantit seulement qu'**une réponse sort**, quel que soit ce que le modèle a fait avant).

## 9. Préoccupations transversales
- **Auth / tenant / routing / plans** : non touchés (cycle de vie du tour, prompt de synthèse interne).
- **Composants** : `AtelierChatService.runLoop` (détection + passe de synthèse), rendu/persistance du
  message (suppression du placeholder muet), réutilisation du strip SF-125-01. Coexiste avec
  `GovernanceEndOfTurnCheckpoint` (dont SF-125-06 retire le caractère bloquant).
