# Mini-spec — F-141 / SF-141-02 Aiguillage : sujet existant / transverse / nouveau / mix

## Identifiant
`F-141 / SF-141-02`

## Feature parente
`F-141` — L'aiguilleur de sujet à la racine

## Statut
`in-progress`

## Date de création
2026-09-22

## Branche Git
`feat/SF-141-02-aiguillage`

---

## Objectif
À la **racine** (terminal du poste), sur un sujet nouveau ou flou, l'agent **classe et propose** une destination — **sujet existant nommé**, **transverse** (carte du poste), **nouveau sujet**, ou **mix** (répartition explicite) — et **attend la validation** du PO avant d'écrire ; en cas d'ambiguïté réelle il **demande**.

---

## Comportement attendu

### Cas nominal
1. Le PO amène un nouveau sujet (ou une info dont la place est floue) au **terminal du poste**.
2. Avant d'écrire, l'agent **découvre les sujets existants** avec les outils existants : liste les **dossiers projet** sous la racine (exploration `bash`/`list_files`), s'appuie sur le sommaire de la carte du poste (F-136) et sur les **sujets Radar** connus (F-99).
3. Il **propose** une destination, **nommée et justifiée**, parmi :
   - **sujet existant** : « ça relève de `data-platform` (parce que…) » ;
   - **transverse** : « c'est un fait du poste → carte racine (`plateformes.md`…) » ;
   - **nouveau sujet** : « je propose de créer le dossier `X` » ;
   - **mix** : « ça touche `data-platform` ET `lzi` → répartition : A→data-platform, B→lzi, C→racine ».
4. Il **attend la validation** (ou une correction) **avant d'écrire**.

### Cas d'erreur / limites
| Situation | Comportement attendu |
|-----------|----------------------|
| Ambiguïté réelle (plusieurs sujets également plausibles) | Poser la question, ne pas deviner |
| Terminal de **projet** (pas la racine) | Pas de routage : dans un sujet, aucune décision de routage (cadrage §5) — la consigne ne s'y injecte pas |
| Racine muette / liste indisponible | Proposer au mieux avec ce qui est connu (carte + Radar), sans inventer un dossier existant |

---

## Critères d'acceptation
- Au **terminal du poste** (racine), la consigne d'aiguillage est **présente** : classer et proposer parmi existant / transverse / nouveau / **mix**, puis **attendre validation**.
- La consigne dit d'**identifier les sujets existants** via les outils existants (liste racine, carte du poste, sujets Radar) avant de proposer.
- La consigne dit de **répartir explicitement** un mix (pas de rangement muet dans un seul).
- Sur un terminal de **projet** ordinaire, la consigne d'aiguillage **n'apparaît pas** (routage sans objet + préfixe lean/cache).
- **Non-régression** : SF-141-01 (annonce de destination) et F-125 (carte silencieuse) restent présents ; strip `fin-de-tour` inchangé.

---

## Plan de test minimal
- **Unitaire (prompt)** : `theSubjectRoutingDoctrineIsPresentOnTheHostTerminal` — la consigne d'aiguillage apparaît sur le terminal du poste (existant/transverse/nouveau/mix + attente validation + découverte des sujets existants).
- **Unitaire (prompt)** : `theSubjectRoutingDoctrineIsAbsentOnAnOrdinaryProject` — elle n'apparaît PAS sur un projet RUNNER ordinaire ni sur un projet SANDBOX.
- **Non-régression** : SF-141-01 (`Dis où tu ranges un fait durable`) et F-125 (`Tenue de la carte, en silence`) présents sur le terminal du poste.
- **Isolation** : inchangée — prompt-only ; le prompt est déjà construit sur `requireOwned` (isolation `user_id`), et la découverte passe par les outils runner du poste possédé (`user_id`+`host_id`).

---

## Tables / endpoints / composants impactés
- `AtelierChatService` : nouvelle constante `SUBJECT_ROUTING_DOCTRINE` + append **conditionnel** (`workspace.isHostTerminal()`) dans `buildSystemPrompt`.
- **Aucune** table, **aucun** endpoint, **aucune** migration.

## Préoccupations transversales
- **Auth / Principal** : inchangé.
- **Contexte tenant** : la découverte des sujets existants passe par les outils du poste possédé (`RunnerToolGateway` sur `host_id`, prompt sur `requireOwned`) — aucun accès cross-tenant introduit. Composants : `buildSystemPrompt` (injection conditionnelle), pas d'autre.
- **Navigation / routing** : aucune.

## Décision technique (hors mini-spec initiale, documentée en PR)
La consigne d'aiguillage est **scopée au terminal du poste** (`isHostTerminal()`), pas injectée partout : dans un sujet le routage n'a aucune ambiguïté (cadrage §5), et un préfixe plus court préserve le cache (F-134) et le coût.

## Mise à jour runner
**Non.** Prompt/doctrine uniquement ; la découverte réutilise les outils runner existants (`bash`/`list_files`), aucun nouvel outil.

## Hors périmètre
- La **création** effective du dossier + gouvernance héritée → SF-141-03.
- Le **reclassement** d'une entrée → SF-141-04.
