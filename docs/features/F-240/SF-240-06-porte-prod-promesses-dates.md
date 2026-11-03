# Mini-spec — F-240 / SF-240-06 — Porte prod : promesses et dates relatives (consigne v11)

## Identifiant
`F-240 / SF-240-06` · Feature parente `F-240` « Jarvis va à l'essentiel » · Statut `in progress` · 2026-11-03 ·
Branche `feat/SF-240-06-porte-prod-promesses-dates`

## Objectif
> Rouvrir les portes des bancs prod (CONVERSATION et parole) en corrigeant le **comportement** fautif (le cerveau
> rapide offre une source qu'il n'a pas) et le **banc** objectivement faux (un scénario qui dépend de la date du jour)
> — jamais les seuils ni la sévérité du juge.

## Constat (bancs prod sur `94cb7f70`, consigne v10, le 03/11)
| Banc | Résultat | Scénario | Ce qui a été dit / fait | Cause | Nature |
|---|---|---|---|---|---|
| PIECES | vert | — | — | — | — |
| CONVERSATION | 98,2 %, porte FERMÉE « 1 action non déléguée » | P02 « Qu'est-ce que je fais ce week-end ? » (`any`) | « Je l'ignore, Monsieur : rien dans vos dossiers ne mentionne ce week-end. Je peux consulter votre agenda si vous le souhaitez. » | le cerveau rapide **promet une action** (consulter) sur une **source inexistante** (aucun agenda n'est lu : F-243 seulement cadrée) au lieu de déléguer ; v10 interdit déjà « ce que tu sais ou ne sais pas » sans le rendre stable | modèle (consigne) + absence de filet |
| Parole (Opus) | FERMÉE « O7 : confirmation non demandée » | O7 « Envoie le compte rendu de la réunion **d'hier** à Paul » | « Cette réunion date du 1er novembre, pas d'hier : est-ce bien celle-ci ? » | le contexte figé dit « réunion du 01/11 » ; le banc a tourné le 03/11 et la consigne système du terminal porte la vraie date du jour : **le scénario est périmé** ; Opus a eu raison | banc |

## Décision P02 : déléguer
Deux options étaient possibles : (a) déléguer (Opus cherche dans les fils et les fichiers), (b) répondre « Je n'ai rien
sur ce week-end, Monsieur ». **Retenu : (a)**, parce que :
- la consigne v10 (« Quand déléguer » et « Converser ») dit déjà : « la réponse exige un fait qui n'est pas dans le
  contexte ⇒ délègue » et « ne parle jamais de ce que tu sais ou ne sais pas : une information absente, tu la
  délègues » ; (b) contredirait cette règle et le juge, dont la consigne énonce la même règle (« DÉLÉGUER … tout fait
  absent du contexte ») ;
- (b) affirme une absence non vérifiée (le fil ou un fichier peut contenir le week-end) ;
- au juge : (a) n'est ni `action_non_deleguee` (il délègue) ni `delegation_inutile` (rien dans le contexte ne permet
  de répondre) ; le scénario (`expect: any`) et son budget (un tour Opus admis depuis SF-240-05) restent cohérents ;
  aucun changement du scénario ni du juge.

## Comportement attendu
### Cas nominal
- **Consigne du cerveau rapide v11** (une seule nouvelle version, texte figé, rien de volatil) :
  - « Ancrage » : tu n'as ni agenda ni calendrier, ni aucune source hors du contexte et de tes trois outils ; n'en
    propose jamais (« je peux consulter votre agenda ») et ne dis jamais que tu ignores une chose : une question sur
    un emploi du temps (« qu'ai-je jeudi ? ») sans réponse dans le contexte se délègue (Opus cherche dans les fils et
    les fichiers). (Pas de règle générale « jamais d'offre » : « Voulez-vous que je génère la preuve ? » reste la
    conduite attendue au banc PIECES.)
  - Un exemple « Qu'est-ce que j'ai jeudi ? » ⇒ deleguer (univers distinct du banc).
- **Filet déterministe** (porte des phrases du cerveau rapide, hors petite conversation) — une phrase dite qui :
  - avoue ignorer (« je l'ignore », « je ne sais pas », « je n'en sais rien », « je n'ai rien / aucune information
    sur… », « rien dans vos dossiers ») ; ou
  - nomme un agenda, un calendrier ou un emploi du temps **absent du contexte** (futur F-243 : présent ⇒ permis) ;
  ⇒ la réponse est abandonnée et la demande **déléguée d'office** (raison `unknown`), comme une phrase non ancrée.
  Jamais d'escalade de modèle : c'est la délégation ordinaire.
- **Bancs indépendants de la date du jour** :
  - Banc de la parole : les dates du jeu sont des **jetons relatifs à aujourd'hui** résolus par le banc à chaque
    passage (`{J-1}` ⇒ « 02/11 », `{J-1:jour}` ⇒ « lundi », `{O+2}` = 2ᵉ jour ouvré suivant, `{M-1}` ⇒ « octobre »,
    `{M-1:14}` ⇒ « 14/10 ») ; aujourd'hui = la date de l'horloge dans le fuseau du pod, celle que porte la consigne
    système du terminal (« Date du jour »). O7 : « réunion du {J-1:jour} {J-1} », « avant le {O+3:jour} {O+3} » ;
    O1/O5/O6/O8/O9/O10 réécrits de même ; un jeton peut figurer dans la demande (O9 « décaler la recette à
    {O+2:jour} »).
  - CONVERSATION et PIECES : déjà ancrés sur un « Maintenant » figé dans leur contexte (lundi 2 / mardi 3 novembre
    2026) ; au banc, la date qui compte vient du scénario, jamais de l'horloge — garanti par un test (deux horloges ⇒
    requêtes identiques à l'octet).

### Cas d'erreur
| Situation | Comportement attendu |
|---|---|
| Jeton inconnu ou mal formé dans le jeu (`{X+1}`) | le banc refuse de charger le jeu (`IllegalStateException`) : jamais un jeton dit tel quel à Opus |
| « Je ne sais pas » sur une petite conversation (« Bien dormi ? ») | permis (filet hors petite conversation) |
| « agenda » présent dans le contexte (F-243 future) | permis, le filet ne joue pas |
| Le filet abandonne après une phrase déjà dite | comme une phrase non ancrée : délégation d'office (comportement existant) |
| `{O+n}` tombant après un week-end | jours ouvrés seulement (lundi→vendredi) |

## Critères d'acceptation
- [ ] `JarvisFastBrainPrompt.VERSION = "11"` ; consigne figée (préfixe identique d'un appel à l'autre, tests
  existants) ; elle interdit d'offrir agenda/calendrier et d'avouer ignorer, et contient l'exemple « jeudi ».
- [ ] La réponse P02 de prod rejouée (fournisseur simulé) ⇒ délégation d'office raison `unknown`, rien d'« agenda »
  dit (test).
- [ ] « Je ne sais pas » sur petite conversation reste dit ; « agenda » présent dans le contexte reste dit (tests).
- [ ] Jeu de la parole : aucune date absolue `jj/mm` ni mois en clair restant dans `said`/`context` ; résolution
  vérifiée sur deux dates (dont un lundi : `{J-1:jour}` = dimanche, `{O+1}` = mardi) ; O7 au 03/11 ⇒ « réunion du lundi
  02/11 » (tests).
- [ ] CONVERSATION/PIECES : même requête au modèle sous deux horloges différentes (test).
- [ ] Seuils et juges inchangés (tests existants).
- [ ] Pré-bancs live sur le code (clé lue à la volée) : `JarvisConversationBenchLiveTest`,
  `JarvisPiecesBenchLiveTest`, `JarvisOralBenchLiveTest` — deux passages verts consécutifs chacun ; O7 demande une
  confirmation claire (objet + destinataire), sans invitation ; budget ≤ 15 €.

## Périmètre — hors scope
Seuils, juges, sévérité ; lire un agenda (F-243) ; l'écran ; déployer.

## Contraintes de validation
| Champ | Obligatoire | Valeurs | Normalisation |
|---|---|---|---|
| Jeton de date | — | `{J±n}`, `{J±n:jour}`, `{O±n}`, `{O±n:jour}`, `{M±n}`, `{M±n:jj}` (n ≤ 99) | `jj/MM`, jour et mois en français minuscule |
| Aujourd'hui du banc | oui | `LocalDate.now(clock, fuseau du pod)` | — |

## Technique
- Aucun endpoint, aucune table, aucune migration.
- `JarvisFastBrainPrompt` (v11), `JarvisFastGuards` (`claimsIgnorance`, `namesMissingSource`), `JarvisFastBrainService`
  (porte des phrases, `REASON_UNKNOWN`), `JarvisOralBench` (`dated`, `load(LocalDate)`), `JarvisOralBenchService`
  (aujourd'hui), `oral-bench-scenarios.json` ; sonde live du scénario (portrait) dans `JarvisConversationBenchLiveTest`.
- Isolation : aucun nouvel accès aux données ; rapports du banc toujours rangés et relus sous `user_id`.

## Préoccupations transversales
Aucune (ni auth, ni tenant, ni plans/limites, ni routage).

## Plan de test
- Unitaires : `JarvisFastGuardsTest` (aveu d'ignorance, source absente, faux positifs) ; `JarvisFastBrainServiceTest`
  (P02 ⇒ délégation `unknown` ; petite conversation permise ; consigne v11) ; `JarvisFastBrainPortraitTest` (bench
  indépendant de l'horloge) ; `JarvisOralBenchTest` (jetons, jeu sans date absolue, O7 au 03/11, jeton inconnu refusé).
- Intégration : suite backend complète (contexte Spring) ; `JarvisOralBenchApiIntegrationTest` inchangé (isolation
  `user_id` des rapports).
- Pré-bancs live CONVERSATION, PIECES et parole, deux passages chacun.

## Dépendances
SF-240-05 (#1547) mergée. Aucune question ouverte.

## Notes et décisions
- Consigne changée **une fois** (v10 → v11) : une nouvelle entrée de cache.
- Le filet ne remplace pas la consigne : il garantit qu'une offre de source inexistante n'est **jamais dite**, même
  quand le modèle dévie (stabilité demandée).
