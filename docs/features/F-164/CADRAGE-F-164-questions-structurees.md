# F-164 — Questions structurées à l'utilisateur (parité Claude Code `AskUserQuestion`)

> **Cadrage du 2026-09-30, validé par le PO.** Document de cadrage — **aucun code produit**.
> Chaque subfeature attend le go du PO avant développement (séquence CLAUDE.md : mini-spec →
> readiness → dev → review → push/release → merge).
> **Écart de parité Claude Code identifié par le PO** : l'agent de l'Atelier ne sait pas encore poser
> de **questions structurées** à l'utilisateur, là où Claude Code dispose de l'outil `AskUserQuestion`.

---

## 0. Le besoin

Donner à l'agent de l'Atelier la capacité de **poser des questions structurées** à l'utilisateur dans
le terminal — comme l'outil `AskUserQuestion` de Claude Code :

- **1 à N questions** dans un même appel (Claude Code plafonne à 4),
- chacune avec des **options** proposées,
- en mode **choix simple** (une seule option) ou **choix multiple** (plusieurs),
- **toujours** une option « **autre : tape ta réponse** » (réponse libre), quelle que soit la question,
- un **rendu graphique soigné** (cartes de questions, choix tactiles, champ libre).

Ce n'est **pas** un moteur d'IA : c'est un **mécanisme d'interaction** — l'agent demande, l'utilisateur
choisit, le tour reprend avec la réponse. Gateway-First et Provider-First restent respectés (§7).

---

## 1. Ce qui existe déjà (bâtir dessus, ne pas réinventer)

Le produit possède **déjà les briques** ; F-164 les **généralise** et les **unifie** — il n'invente ni la
pause d'un tour, ni le rendu de choix, ni le pilotage cross-device.

### 1.1 La pause interrogeable d'un tour — `RunnerConfirmationGate` (F-84)

La **porte d'autorisation d'outil** (Autoriser / Toujours autoriser / Refuser) met **déjà** un tour
**EN ATTENTE** d'une réponse humaine, **côté serveur**, pilotable **depuis n'importe quel appareil**
(F-84 « le tour vit dans le flux » ; le tour vit côté serveur, survit à l'attach/detach, un téléphone
au navigateur peut répondre à la place du PC). **C'est LE mécanisme de pause/reprise à réutiliser.**

**Limite actuelle, cœur de F-164** : la porte ne gère qu'**une seule** pause par franchissement ; F-164
doit la **généraliser aux pauses répétées dans un même tour** (§3, questionnement itératif).

### 1.2 Des prompts structurés AD-HOC déjà présents (cas particuliers à unifier)

- la suggestion « **Nouveau départ** » (3 options) — F-117 / SF-117-03 ;
- le **choix de reprise** d'un fil — F-39 ;
- la **porte d'autorisation** elle-même (3 options).

Ce sont des **cas particuliers** codés à la main. F-164 apporte le **mécanisme GÉNÉRAL** dont ils sont
des instances ; SF-164-04 (option) propose de les **ramener** sous ce mécanisme unique.

### 1.3 Ce qui N'existe PAS

Il n'existe **aucun outil générique « poser une question »** dans le catalogue de l'Atelier
(`AtelierChatService` : `read_file`, `list_files`, `search_files`, `grep`, `glob`, `explore`, `bash`,
`recall`, `edit_file`, `write_file`, `multi_edit`, `render_diagram`, `page_publish`, `record_blocker`,
`email_me`, `set_plan`, `web_search`) — **aucun `ask_user` / `demander`**. C'est le manque que F-164 comble.

### 1.4 Briques d'amélioration réutilisables

- **F-84** — pause/reprise cross-pod (le socle) ;
- **F-153** — Web Push (« une question t'attend ») ;
- **F-151 / F-152** — version mobile (PWA, responsive) : les choix tactiles y sont idéaux ;
- **DESIGN_SYSTEM.md** — jetons `--cg-*` (obligatoire pour tout rendu).

---

## 2. Cas d'usage (où ça sert dans notre application)

1. **Cadrage / découpage de features** — *le cas roi* (« combien de subfeatures ? », « quel périmètre
   pour la V1 de cette feature ? »).
2. **Demande ambiguë** — quel compte AWS ? quelle branche ? lequel de ces fichiers ?
3. **Avant une action risquée / destructive** — supprimer / écraser / déployer / `drop` →
   *Appliquer / Dry-run / Annuler*.
4. **Choix technique avec arbitrage** — approche A vs B, quel environnement.
5. **Priorisation** — ordre de livraison, quoi inclure / exclure.
6. **Récupération d'erreur multi-voies** — réessayer / autre méthode / abandonner.
7. **Mise en service** (runner / Vigie) — OS, format de paquet, proxy.

**+ à terme** : unifier les prompts ad-hoc existants (§1.2) sous ce mécanisme (SF-164-04).

---

## 3. Exigences (dont deux ajoutées par le PO)

- **Déclenchement par l'agent** : c'est un **OUTIL** que le modèle appelle **quand il se juge bloqué sur
  une décision qui appartient à l'utilisateur**.
- **Déclenchement manuel (ajout PO)** : quand l'utilisateur écrit p. ex. *« pose-moi toutes les questions
  que tu veux pour bien comprendre tel sujet »*, le modèle **DOIT reconnaître ce signal** et utiliser
  l'outil. **Pas de bouton séparé** — c'est **le même outil**, déclenché par la demande **+ une consigne
  système** qui apprend au modèle à saisir ce signal.
- **Questionnement UNITAIRE / itératif (ajout PO)** : l'outil doit être **appelable PLUSIEURS FOIS dans un
  même tour** — poser une question, recevoir la réponse, **RAISONNER dessus**, poser la suivante, etc. Le
  tour doit donc savoir se **mettre en pause et reprendre plusieurs fois** de suite. *(La porte
  d'autorisation ne gère aujourd'hui qu'**une** pause ; à **généraliser** aux pauses répétées.)* Supporter
  **AUSSI le lot** (1 à 4 questions d'un coup, comme Claude Code).
- **Option libre systématique** : **toujours** un choix « autre / tape ta réponse ».
- **Format structuré par DÉFAUT — règle OBLIGATOIRE (ajout PO)** : ce n'est **pas une simple
  possibilité**. **Dès que l'agent a une LISTE de questions à poser, OU des questions dont il peut
  PROPOSER les réponses, il DOIT passer par le format structuré `demander` — jamais de la prose.** La
  **prose reste réservée** aux questions **vraiment ouvertes**, sans réponse proposable. Formulation à
  reprendre **textuellement** dans la consigne système de SF-164-01 : *« toute question à réponses
  proposables, a fortiori une liste, passe par l'outil structuré »*. Cette règle **prime** sur le confort
  de rédaction : une liste de questions rendue en prose est un **défaut**, pas un style.

---

## 4. Améliorations (calibrées sur l'usage réel)

- **Mobile** : les choix tactiles sont idéaux sur téléphone → **synergie F-151/152/153** ; cibles
  **≥ 44 px**, rendu responsive, **ne pas régresser SF-158**.
- **Cross-device (F-84)** : la question **vit côté serveur** → répondable depuis **téléphone OU PC**,
  **survit à l'attach/detach** ; **+ notification push (F-153)** « une question t'attend ».
- **Décider-par-défaut si non-attendu (CRUCIAL pour les vagues autonomes)** : une question **bloquante**
  figerait un tour autonome **à l'infini**. Politique :
  - **quelqu'un est là** → on **demande** (comportement nominal) ;
  - **absent / timeout / mode autonome** → on prend l'**option recommandée** (chaque question en porte
    **une**) et on **FLAGUE** (doctrine maison « **décider-par-défaut avec flag** »).
- **Discipline anti-spam** : consigne système « **ne demande QUE si vraiment bloqué** sur une décision de
  l'utilisateur ; sinon **décide** » (comme la consigne de l'outil chez Claude Code).

---

## 5. Découpage (validé PO)

| SF | Intitulé | Contenu |
|----|----------|---------|
| **SF-164-01** | L'outil `demander` (cœur) | **Schéma** : 1 à 4 questions ; par question — **intitulé**, **options**, **mode simple/multiple**, **option libre** (systématique), **option recommandée** (pour le défaut). Outil **câblé dans la boucle** (`runLoop`), qui met le tour **EN PAUSE** en **réutilisant / généralisant le mécanisme de la porte (F-84)** pour des **pauses répétées dans un tour**, puis **reprend avec les réponses**. **Consigne système** : (a) **règle par défaut OBLIGATOIRE** — *« toute question à réponses proposables, a fortiori une liste, passe par l'outil structuré `demander`, jamais la prose ; la prose est réservée aux questions vraiment ouvertes »* ; (b) **signal de déclenchement manuel** (§3) ; (c) **discipline anti-spam** (§4). |
| **SF-164-02** | Rendu terminal (desktop + mobile) | **Cartes de questions**, **choix tactiles** simple/multiple, **champ libre**. **Design system** strict (jetons `--cg-*`), responsive, cibles **≥ 44 px**, pas de régression SF-158. |
| **SF-164-03** | Politique décider-par-défaut + flag + push | **Option recommandée = défaut** au **timeout** / en **vague autonome** ; **flag** posé sur la décision par défaut (doctrine maison) ; **notification push** (F-153) « **question en attente** ». |
| **SF-164-04** *(option)* | Unifier les prompts ad-hoc | Ramener **Nouveau départ** (F-117/SF-117-03) et **choix de reprise** (F-39) — voire la porte — **sous ce mécanisme**. À faire seulement après SF-164-01→03 stabilisées. |

**Ordre** : SF-164-01 → SF-164-02 → SF-164-03 → (SF-164-04 optionnelle).

**Dépendances** : SF-164-01 réutilise **F-84** (pause/reprise) ; SF-164-02 dépend de **DESIGN_SYSTEM** et
de la **version mobile** (F-151/152) ; SF-164-03 dépend de **F-153** (push) pour le volet notification.

---

## 6. Périmètre

### Dans le périmètre
- Un **mécanisme d'INTERACTION** générique : l'agent **demande**, l'utilisateur **choisit** (choix simple
  ou multiple), toujours avec une **réponse libre** possible.
- **Pauses répétées** d'un tour (questionnement itératif) **et** lot de 1 à 4 questions.
- **Cross-device** (F-84) et **notification** (F-153).
- **Politique décider-par-défaut avec flag** pour les vagues autonomes.
- **Isolation `user_id`** (et `workspace_id`) sur **tout état persistant** lié à une question.

### Hors périmètre
- **Refondre toute l'UX du terminal** (F-164 ajoute un mécanisme, ne réécrit pas l'écran).
- Les **questions inter-projets** (poser une question qui traverse plusieurs postes/projets).
- Réactiver un moteur de sous-agents ou tout ce qui relèverait d'un « moteur IA ».

---

## 7. Architecture & garde-fous (conformité CLAUDE.md)

- **Gateway-First** — F-164 **orchestre** une interaction ; le backend **ne devient pas un moteur d'IA**.
  La *décision* de poser une question appartient au **modèle** (via l'outil) ; la gateway ne fait que
  **suspendre le tour, présenter les choix, collecter la réponse, reprendre**.
- **Provider-First** — Claude Code fournit déjà ce patron (`AskUserQuestion`) ; on **relaie le patron**
  (un outil que le modèle appelle), on ne réimplémente pas un dialogue « intelligent ». *(Note : c'est un
  patron d'outil, pas une capacité de génération à relayer par HTTP — mais l'esprit Provider-First tient :
  on suit le contrat d'outil du fournisseur plutôt que d'inventer un mécanisme divergent.)*
- **Provider Independence** — l'outil est un **outil de la boucle**, indépendant du fournisseur concret ;
  aucun code métier ne dépend directement d'Anthropic.
- **Isolation multi-tenant** — toute question **en attente** persistée (pour survivre à l'attach/detach,
  au timeout, au push) est **filtrée `user_id` + `workspace_id`** ; une question d'un poste ne fuit jamais
  vers le tour d'un autre, y compris entre deux postes du **même** utilisateur.
- **Async / non bloquant** — une question ne doit **jamais** figer un tour autonome à l'infini
  (SF-164-03 : timeout + option recommandée + flag).
- **Design system** — SF-164-02 : jetons `--cg-*` uniquement, composants Material, responsive ≥ 44 px,
  **aucune couleur/police hors DESIGN_SYSTEM**.

### Note de cohérence `ARCHITECTURE_CANONIQUE.md`
Le cadrage **ne crée aucune table à ce stade** (décision documentaire seulement). Si SF-164-01/03
introduit un **état persistant** de « question en attente » (probable, pour le cross-device et le
timeout), la **création de cette table sera reflétée dans `ARCHITECTURE_CANONIQUE.md`** au merge de la
subfeature concernée (étape 6 CLAUDE.md). **Aucune incohérence détectée** avec `ARCHITECTURE_CANONIQUE.md`
au moment du cadrage : F-164 est un mécanisme d'interaction, cohérent avec Gateway-First, et n'entre en
conflit avec aucune capacité documentée.

---

## 8. Ce qu'on ne fait PAS
- Un **bouton** « poser une question » séparé de l'outil (le déclenchement manuel passe par la **demande
  en langage naturel + la consigne système**, §3).
- Une question **bloquante sans échappatoire** (viole la politique décider-par-défaut, §4).
- Un **dialogue « intelligent »** réimplémenté côté backend (violerait Gateway-First).
- Refondre l'UX du terminal ou traiter les questions **inter-projets** (§6, hors périmètre).

---

## 9. Statut
**Cadrée / À faire.** Inscrite à `docs/PRODUCT_SPEC.md` (règle d'existence CLAUDE.md). Découpage validé PO
(SF-164-01 → 04). **Aucun développement engagé** — chaque SF suivra la séquence obligatoire.
