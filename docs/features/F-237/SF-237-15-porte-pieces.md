# Mini-spec — F-237 / SF-237-15 — Porte du banc PIECES : causes et correctifs

## Identifiant
`F-237 / SF-237-15` · Feature parente `F-237` · Statut `in-review` · 2026-11-02 · Branche
`feat/SF-237-15-porte-pieces` · Correctif de la porte du banc PIECES (SF-237-13) mesurée en prod (staging-45296f70).

## Objectif
> Faire passer la porte PIECES (SHOW, NAVIGATE, PROOF ≥ 90 % ; zéro délégation de preuve sans « oui ») sans faire
> baisser la porte CONVERSATION, en corrigeant les causes mesurées plutôt qu'en gonflant la consigne.

## Constat (banc prod + sonde en direct sur le code prod, Sonnet 5.5)
Sonde `JarvisPiecesBenchLiveTest#probe`, 3 rejeux par cas en échec :

| Cas | Ce qui se passe | Cause |
|---|---|---|
| S13, N01, N03 (+ S01 par intermittence) | le modèle dit « Voici « Cours 2 : Terraform », de lundi 14:00. » puis appelle `afficher` ; la porte des phrases juge « 14:00 » **non ancré** ⇒ abandon ⇒ délégation d'office (`grounding`) ; l'appel `afficher` du même tour est jeté | **bogue de garde** : `JarvisFastGuards.containsNumber` recolle un nombre en fin de ligne au numéro de la ligne suivante (`14:00\n3.` ⇒ `14003`) ; et `9h55` ≠ `09:55` (zéro de tête) |
| N04 | « Va au moment où il parle du state » ⇒ le modèle délègue (« je n'ai pas le découpage ») | consigne : la navigation par mots d'un chapitre n'est pas dite |
| P03 | « Prouve-moi que le portail répond » ⇒ le modèle délègue un test en direct + captures | consigne : « prouve-moi » n'est pas rangé dans le doute (montrer les preuves existantes) |
| P06 | « …tu as vérifié ? » sur un chantier **sans preuve** ⇒ délégation d'une vérification avec captures, sans « oui » (2 fois sur 3) | aucune garde serveur ; la consigne dit à la fois « vérifie ⇒ délègue » et « doute ⇒ propose » |

Le délai du premier jeton (1,3 à 2,5 s, sous le seuil de 8 s) et la reconnaissance des appels d'outil sont hors de cause.

## Comportement attendu

### 1. Ancrage des nombres (correctif de garde)
`containsNumber` cherche le nombre parmi les suites de nombres **d'une même ligne** (séparateurs espace, tabulation,
`.`, `,`, `:`, `h`), sur toute sous-suite contiguë, zéros de tête ignorés. « 14:00 » en fin de ligne, « 9h55 » contre
« 09:55 » sont ancrés ; « 14003 » ne l'est jamais par recollement de deux lignes.

### 2. Une phrase non ancrée ne jette plus l'écran
Si la porte des phrases rejette une phrase (fait non ancré) dans un tour qui **montre** (appel `afficher` valide, ce
tour-ci ou un précédent de la même réponse) et ne délègue pas : la phrase reste retirée (jamais dite), les ordres
d'écran sont relayés, la réponse est ce qui a été dit d'ancré, sinon « Voici. ». Aucune escalade vers Opus (≈ 1 €)
pour une phrase de légende. Toute autre raison d'abandon (texte d'outil, refus, délai…) reste une délégation d'office.

### 3. Garde serveur « preuve sans oui » (déterministe)
Une parole de **doute** (« tu es sûr », « tu as vérifié », « c'est vérifié », « prouve », « preuve(s) »), sans
demande explicite d'agir (« génère », « capture », « teste », « vérifie » à l'impératif), ne se délègue jamais :
- **avant l'appel au modèle** : si le bloc `## Pièces` du contexte dit que le chantier visé (nommé dans la parole,
  sinon « Chantier courant ») a **0 preuve** ⇒ réponse fixe, sans appel : « Le chantier N n'a encore aucune preuve en
  image. Voulez-vous que je la génère, Monsieur ? » ;
- **après l'appel** (filet) : si le modèle délègue (ou si une délégation d'office survient) ⇒ si le chantier a des
  preuves, ordre d'écran `galerie` {chantier N, preuves, appui} et « Voici les preuves du chantier N. » ; sinon la
  proposition ci-dessus ; chantier ou preuves inconnus ⇒ « Voulez-vous que je vérifie, Monsieur ? ».
Le « oui » au tour suivant n'est pas un doute : la délégation part (P07 inchangé). « Vérifie » seul reste la
délégation demandée (D9d, inchangée).

### 4. Consigne v8 (deux précisions, préfixe stable)
- Naviguer ne se délègue jamais : « va au moment où il parle du state » = action `aller`, chapitre « state ».
- « Prouve-moi que… » est un doute : montrer les preuves existantes ; sans preuve, proposer.
Texte figé, version 7 ⇒ 8 (une nouvelle entrée de cache, rien de volatil).

### 5. Diagnostic du banc
Le commentaire d'un verdict en délégation d'office porte sa raison (« aucun affichage (délégation d'office :
grounding) ») — vocabulaire fermé, aucun contenu.

### Cas d'erreur
- Bloc `## Pièces` absent ou illisible ⇒ preuves inconnues ⇒ pas de réponse fixe avant l'appel ; filet : proposition
  de vérifier.
- Parole de doute + demande explicite (« tu es sûr ? génère la preuve ») ⇒ garde inactive, le modèle décide.
- Salutation, plafond, quota : chemins inchangés (la garde ne s'applique qu'à une délégation ou avant l'appel).

## Contraintes de validation
| Champ | Règle |
|---|---|
| Parole de doute | liste fermée (normalisée sans accents) ; testée |
| Demande explicite | impératifs fermés : génère, capture, teste, vérifie en tête de phrase |
| Chantier visé | « chantier N » de la parole, sinon « Chantier courant : N » du contexte, sinon celui de la requête |

## Critères d'acceptation
- [ ] `containsNumber` : « 14:00 » en fin de ligne et « 9h55 »/« 09:55 » ancrés ; pas de recollement entre lignes (tests).
- [ ] Phrase non ancrée + `afficher` ⇒ ANSWER avec l'ordre relayé, jamais DELEGATE (test service).
- [ ] Doute sur chantier sans preuve ⇒ proposition fixe, aucun appel au modèle (test) ; doute + délégation du modèle ⇒ jamais DELEGATE (test) ; « oui » ⇒ délégation conservée (test).
- [ ] Consigne v8 figée, outils inchangés (tests existants).
- [ ] Pré-banc PIECES en direct (code prod, Sonnet 5.5) : **2 passages** porte verte (≥ 90 % par catégorie, 0 faute grave).
- [ ] Pré-banc CONVERSATION en direct : **1 passage** porte verte (≥ 90 %, zéro faute).
- [ ] Suite backend complète verte.

## Préoccupations transversales
| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| Auth / Principal | Non | — |
| Contexte tenant | Non | aucune lecture nouvelle : la garde lit le contexte déjà construit sous `user_id` |
| Plans / limites | Oui | coût : moins de délégations à Opus ; la réponse fixe n'appelle aucun modèle ; budget / quota (`budgetState`, `QuotaService`) inchangés et toujours vérifiés avant |
| Navigation / routing | Non | — |

## Tables / endpoints / composants
Aucune table, aucun endpoint. `JarvisFastGuards`, `JarvisFastBrainService`, `JarvisFastBrainPrompt`,
`JarvisPiecesBench`, nouveau `JarvisProofGuard` (pur). Test live `JarvisPiecesBenchLiveTest` (désactivé par défaut).

## Plan de test
- Unitaires : `JarvisFastGuardsTest` (nombres), `JarvisProofGuardTest` (doute, demande explicite, chantier, preuves,
  décisions), `JarvisFastBrainServiceTest` (phrase non ancrée + afficher ; garde preuve avant / après l'appel ; oui),
  `JarvisPiecesBenchTest` (commentaire avec raison).
- Isolation utilisateur : inchangée (aucune lecture nouvelle) ; tests d'API existants rejoués par la suite complète.
- En direct : `JarvisPiecesBenchLiveTest` ×2, `JarvisConversationBenchLiveTest` ×1 (clé lue à la volée).

## Hors périmètre
Déploiement (fait par le PO) ; tout changement de modèle ; le jeu de cas du banc (inchangé) ; la navigation par
transcription côté écran.
