# Mini-spec — F-39 / SF-39-15 — Un message ne peut plus coûter sans limite, et ce qu'il coûte se voit

## Identifiant

`F-39 / SF-39-15` — **lot 8 · Coût visible** (`docs/features/F-39/CADRAGE.md` §5)

## Feature parente

`F-39` — L'Atelier comme harnais

## Statut

`ready`

## Date de création

2026-09-06

## Branches Git

`feat/SF-39-15-plafond-message` (backend) · `feat/SF-39-15-plafond-message-ui` (écran)

---

## Objectif

Borner ce qu'un **seul message** peut consommer dans la boucle maison, et **montrer** cette
consommation — pendant le tour et à sa fin — comme le budget de session (F-36) est déjà montré.

---

## Le manque, constaté dans le code

Depuis le lot 4 (SF-39-07/08), la boucle maison est le moteur du terminal de l'Atelier. Elle porte
**trois** garde-fous, et aucun ne borne la dépense :

| Garde-fou | Ce qu'il borne | Fichier |
|---|---|---|
| `maxIterations` (30) | Le **nombre** d'allers-retours — pas leur taille | `AtelierChatService` |
| `TURN_BUDGET_MS` (10 min) | La **durée** — une itération lente coûte peu, une itération énorme coûte cher en une seconde | `AtelierChatService` |
| `quotaService.assertWithinQuota` | **Rien pour ce message** : il est vérifié *avant* le tour et enregistré *après*. Un seul message peut donc dépasser le quota mensuel entier | `AtelierChatService` |

C'est exactement le défaut que F-36 a corrigé pour le chemin **Managed Agents** (`SessionBudget`,
verrou pré-requête posé chez le fournisseur) — et que la boucle maison n'a **jamais** reçu, alors
qu'elle est devenue le moteur principal. Le cadrage le dit (D3) : *« Un plafond de dépense par
message, affiché comme l'est déjà le budget de session (F-36), viendra ensuite. »*

Second manque, d'affichage : le flux `chat/stream` **ne relaie aucune consommation**. Le
commentaire posé en SF-39-08 le dit noir sur blanc (`atelier.component.ts`) :
*« Pas de `cost` : la boucle maison ne relève pas la consommation d'un tour. »* L'acquis §4 n°6
(« Coût du tour affiché », SF-30-05) et la part « consommation » de l'acquis n°5 (ligne vivante,
SF-30-13) ne valent donc **que** pour le moteur hébergé — c'est-à-dire pas pour celui qui exécute
réellement.

---

## Comportement attendu

### Cas nominal

1. À l'ouverture d'un tour, la boucle calcule un **plafond de tokens pour ce message**
   (`AtelierTurnBudget`) :
   - plafond de base : `app.atelier.max-turn-tokens` (défaut **1 500 000**) ;
   - en mode **Hosted**, borné en plus par le **quota restant** de l'utilisateur ;
   - en mode **BYOK**, le quota n'est pas consulté (les tokens sont sur le compte du client) : seul
     le plafond de base s'applique.
2. Après chaque itération, la consommation du tour (`inputTokens + outputTokens`, cache compris —
   SF-39-01) est cumulée et **relayée** au fil de l'eau (événement SSE `progress`).
3. **Avant** chaque itération suivante, la boucle projette le coût de l'itération à venir par la
   **plus grosse itération déjà observée** dans ce tour. Si `cumul + projection > plafond`, le tour
   s'arrête à cette frontière sûre, sur un message explicite.
4. Le tour se termine : `done` porte `inputTokens`, `outputTokens`, `activeSeconds` et
   `budgetReached`. L'écran affiche le coût du tour (`m:ss · N tokens`) et, le cas échéant, la bande
   « plafond atteint ».
5. La consommation, la durée et le drapeau sont **persistés** (`atelier_messages.terminal_json`,
   colonne existante) : ils survivent au rechargement.

### Cas d'erreur / cas limites

| Situation | Comportement attendu |
|---|---|
| Plafond atteint en cours de tour | Le tour **s'arrête** ; le travail déjà fait est conservé et persisté ; réponse : « Ce message a atteint son plafond de consommation… » ; `budgetReached = true` |
| Quota restant très faible (< une itération) | La **première** itération est toujours permise : refuser avant tout appel produirait un tour vide, illisible comme une panne. Dépassement borné à une itération, décompté par le quota comme aujourd'hui |
| Mode BYOK | Plafond de base seul ; aucun accès au quota (SF-28-06 inchangé) |
| Budget de **temps** atteint (10 min) | Comportement **inchangé** : réponse « Le temps imparti… », `budgetReached = false` — ce n'est pas un plafond de dépense |
| Tour interrompu (`Ctrl-C`, F-32) | Comportement inchangé ; la consommation déjà faite est relevée et affichée (le tour est facturé) |
| Réglage absent, nul ou négatif | Retombe sur le défaut ; un réglage > 10 000 000 est ramené à cette borne (au-delà, `maxIterations` et le budget de temps auraient tranché) |
| `progress` non consommé par le client | Sans effet : l'événement est additif, `onProgress` est optionnel |
| `terminal_json` illisible au rechargement | Tour sans transcription ni coût (comportement existant), jamais une erreur d'historique |

---

## Critères d'acceptation

- [ ] **CA1** — Un tour dont le cumul + la projection dépasse le plafond s'arrête sans appeler à nouveau le fournisseur.
- [ ] **CA2** — Le message de fin distingue le plafond de **consommation** du budget de **temps** (deux textes distincts).
- [ ] **CA3** — La première itération est toujours exécutée, même avec un quota restant minuscule.
- [ ] **CA4** — En mode Hosted, le plafond ne dépasse jamais le quota restant.
- [ ] **CA5** — En mode BYOK, le quota n'est **pas** lu et le plafond de base s'applique.
- [ ] **CA6** — Le flux SSE émet `progress` après chaque itération, avec le cumul de tokens.
- [ ] **CA7** — `done` porte `inputTokens`, `outputTokens`, `activeSeconds`, `budgetReached` ; les champs sont **additifs** (un client antérieur est inchangé).
- [ ] **CA8** — Consommation, durée et `budgetReached` sont persistés et relus au rechargement.
- [ ] **CA9** — Isolation : le plafond est dérivé du quota de l'utilisateur du **contexte de sécurité**, jamais d'un identifiant venu du client ; `requireOwned` reste le premier geste du tour.
- [ ] **CA10** — Écran : le coût du tour s'affiche pour la boucle maison (acquis §4 n°6) et la ligne vivante affiche le cumul pendant le tour (acquis §4 n°5).
- [ ] **CA11** — Écran : la bande « plafond atteint » s'affiche sur les deux moteurs, avec un texte de reprise adapté (pas de « même sandbox » sur la machine de l'utilisateur).
- [ ] **CA12** — Aucun secret, aucune clef, aucun montant en dollars exposé au client.
- [ ] **CA13** — Les 13 acquis §4 restent verts (`acquis-f30.spec.ts`).

---

## Périmètre

### Hors scope (explicite)

- **Aucune tarification en dollars exposée à l'utilisateur** (voir D-L8-4).
- **Aucun plafond réglable par l'utilisateur** (ce serait un écran de réglage, pas ce lot).
- Persistance des **blocs** de transcription de la boucle maison : manque distinct, antérieur à ce lot.
- Chemin Managed Agents : inchangé (il a déjà son `SessionBudget`, F-36).
- Lot 7 (liste de tâches, sous-agents) : hors de cette subfeature.

---

## Contraintes de validation

| Réglage / champ | Obligatoire | Bornes | Valeurs autorisées | Normalisation |
|---|---|---|---|---|
| `app.atelier.max-turn-tokens` | Non | `[1 ; 10 000 000]` | entier | absent, nul ou négatif → **1 500 000** ; au-delà de 10 000 000 → ramené à 10 000 000 |
| plafond effectif du tour | — | `≥ 1` | dérivé | Hosted : `min(réglage, quota restant)` ; BYOK : `réglage` |
| `done.inputTokens` / `outputTokens` | Oui | `≥ 0` | entier | cumul du tour, cache compris (SF-39-01) |
| `done.activeSeconds` | Oui | `≥ 0` | entier | durée d'horloge du tour, arrondie à la seconde inférieure |
| `done.budgetReached` | Oui | — | booléen | vrai **uniquement** sur le plafond de consommation (D-L8-5) |
| `progress.tokens` | Oui | `≥ 0` | entier | cumul croissant, émis après chaque itération |

Notes :

- Aucune contrainte structurante n'est laissée indéterminée : le plafond, ses bornes et son défaut
  sont tranchés ici (D-L8-1) ; aucune entrée de `docs/OPEN_QUESTIONS.md` n'est ouverte par ce lot.
- Aucun champ n'est saisi par l'utilisateur : le plafond est un réglage serveur, les compteurs sont
  mesurés. Il n'y a donc ni format à valider, ni unicité à garantir.

---

## Technique

### Endpoints impactés

| Méthode | URL | Changement |
|---|---|---|
| POST | `/api/workspaces/{id}/chat/stream` | Nouvel événement SSE `progress` ; `done` **enrichi** (additif) |
| POST | `/api/workspaces/{id}/chat` | Réponse **enrichie** (additif) |
| GET | `/api/workspaces/{id}/chat` | `terminal` désormais renseigné pour les tours de la boucle maison (champ existant) |

### Tables impactées

| Table | Opération | Notes |
|---|---|---|
| `atelier_messages` | INSERT | Colonne **existante** `terminal_json` désormais renseignée par la boucle maison |
| `usage_counters` | inchangé | Le décompte du quota ne change pas |

### Migration Liquibase

- [x] **Non applicable** — aucune colonne, aucune table. Le plafond est un **réglage serveur** ;
  la consommation d'un tour se range dans la colonne d'affichage existante.

### Composants Angular

- `AtelierComponent` — `onProgress` câblé sur `streamChat`, `cost` et `budgetReached` posés à `onDone`.
- `AtelierTerminalComponent` — texte de reprise de la bande « plafond » adapté au moteur.
- `AtelierService` — routage de l'événement `progress`, lecture des champs additifs de `done`.
- `toThreadItem` — le coût est relu même quand la transcription est vide.

---

## Préoccupations transversales

| Préoccupation | Touchée ? | Composants impactés |
|---|---|---|
| Auth / Principal | **Non** | Aucun changement : `currentUser.requireId()` inchangé |
| Contexte tenant | **Oui, en lecture** | `AtelierChatService.runLoop` (déjà `requireOwned` en premier geste) ; `QuotaService.currentUsage(userId)` appelé avec l'utilisateur du contexte de sécurité, comme `AtelierSessionService.sessionBudget` |
| Plans / limites | **Oui** | Nouveau **gate** de consommation par message. Appels aux limites existants passés en revue : `QuotaService.assertWithinQuota` (pré-vol, inchangé), `recordUsage` (post-run, inchangé), `assertWithinSandboxLimit` (chemin Managed Agents, hors sujet), `AtelierSessionService.sessionBudget` (chemin Managed Agents, inchangé). Le nouveau gate **s'ajoute**, il n'en remplace aucun |
| Navigation / routing | **Non** | Aucune route, aucun garde |

---

## Plan de test

### Tests unitaires — `AtelierTurnBudgetTest`

- [ ] Plafond de base retenu en BYOK (quota non consulté).
- [ ] Plafond borné par le quota restant en Hosted.
- [ ] Première itération toujours permise, même à plafond nul.
- [ ] `exceeded` faux tant que `cumul + projection <= plafond`, vrai au-delà.
- [ ] Réglage nul / négatif / démesuré ramené aux bornes.

### Tests unitaires — `AtelierChatServiceBudgetTest`

- [ ] Un tour s'arrête sur le plafond, avec le message de plafond (≠ message de temps).
- [ ] Le tour arrêté est **persisté** avec `budgetReached` dans `terminal_json`.
- [ ] `progress` est émis après chaque itération, cumul croissant.
- [ ] Le quota est décompté de ce qui a réellement été consommé, plafond atteint ou non.
- [ ] En BYOK, aucun appel à `QuotaService`.
- [ ] Un tour ordinaire (sous le plafond) produit un corps d'appel **strictement identique** à celui d'avant.

### Tests d'intégration — `AtelierChatApiIntegrationTest`

- [ ] `POST /chat` d'un autre utilisateur → 404 (isolation, non-régression).
- [ ] `GET /chat` restitue `terminal.inputTokens` / `budgetReached` après un tour.

### Tests frontend

- [ ] `AtelierService` route `progress` vers `onProgress` et lit les champs additifs de `done`.
- [ ] `AtelierComponent` pose `cost` et `budgetReached` sur le tour terminé de la boucle maison.
- [ ] `toThreadItem` rend le coût d'un tour sans blocs.
- [ ] `AtelierTerminalComponent` : texte de reprise adapté au moteur.
- [ ] `acquis-f30.spec.ts` : les 13 acquis restent verts.

### Isolation utilisateur

- [x] **Applicable** — `requireOwned(userId, workspaceId)` est le premier geste de `runLoop`
      (non-régression testée) ; le quota lu est celui du contexte de sécurité.

---

## Dépendances

### Subfeatures bloquantes

- `SF-39-01` (comptage cache-compris) — **done** : sans lui, le cumul sous-estimerait de ~90 %.
- `SF-39-08` (écran unique) — **done** : c'est lui qui donne à la boucle maison la vue terminal où le coût s'affiche.
- `SF-36-01` / `SF-36-04` — **done** : le motif d'écran « plafond atteint » est repris, pas réinventé.

### Questions ouvertes impactées

- Aucune. `OQ-13` (smoke F-38) n'est pas touchée.

---

## Notes et décisions

### D-L8-1 — Le plafond est exprimé en **tokens traités**, pas en dollars

Le cadrage parle de « plafond de dépense » en dollars (~6,75 $ le message sans cache). La boucle
maison ne dispose pourtant que d'**un** relevé : le compteur de tokens, qui — décision **D3 de
SF-39-01**, délibérée — additionne `input_tokens`, `cache_creation_input_tokens` **et**
`cache_read_input_tokens`, parce que *« le quota mesure ce qui a été traité, pas ce que le
fournisseur facture »*.

Convertir ce compteur en dollars par le taux mélangé de F-36 (`cost-per-million-tokens`, 9 $/M)
donnerait un chiffre faux d'un **ordre de grandeur** : un tour de 30 itérations pèse ~1,35 M tokens
*traités* mais ~1,27 $ *facturés*, l'essentiel étant relu du cache au dixième du tarif. Un plafond à
2 $ converti ainsi vaudrait 222 000 tokens et **couperait vers la huitième itération** — c'est-à-dire
qu'on couperait précisément les tours que le cache de prompt (lot 1) venait de rendre abordables. Le
remède serait pire que le mal.

Le plafond est donc dit dans l'unité que la boucle mesure **et** que le produit facture déjà à
l'utilisateur (le quota est en tokens). Défaut **1 500 000** — calibré sur l'usage réel du cadrage
§1.2 : contexte maximal observé **900 519** tokens, estimation D3 d'un tour de 30 itérations
≈ **1,35 M** tokens d'entrée. Un tour ordinaire ne le voit jamais ; un tour parti en vrille s'y
arrête.

*Alternative écartée* : exposer les quatre tarifs du fournisseur (entrée, écriture de cache, lecture
de cache, sortie) pour calculer un coût fidèle. C'est une **table de prix par modèle** à maintenir,
là où F-36 a explicitement choisi un taux mélangé unique ; et cela n'améliorerait pas le geste, qui
est d'arrêter un tour aberrant. **Réversible** : le jour où l'on veut des dollars, on ajoute les
compteurs séparés à `AgentTurn` sans toucher au reste.

### D-L8-2 — Le plafond **projette** au lieu de constater

Le verrou de F-36 est *pré-requête* parce qu'il vit chez le fournisseur. Ici, la boucle appelle
l'API Messages brute : rien ne peut refuser un appel avant qu'il parte. Constater après coup
autoriserait une itération entière au-delà du plafond — jusqu'à ~200 000 tokens.

La boucle projette donc l'itération à venir par la **plus grosse déjà observée dans ce tour** — un
majorant, et non une moyenne, parce que le contexte d'un tour **croît** à mesure que les résultats
d'outils s'empilent. Elle s'arrête *avant* l'appel qui ferait franchir le plafond.
*Alternative écartée* : la moyenne des itérations passées, qui sous-estime systématiquement la
suivante et rendrait le plafond décoratif. **Réversible** (une ligne).

### D-L8-3 — La **première** itération est toujours permise

Sans elle, un utilisateur à quota presque épuisé recevrait un tour qui n'a rien fait, rien dit et
rien coûté : illisible, et lu comme une panne. C'est le rôle que joue `min-run-cost` chez F-36
(plancher de 0,10 $), obtenu ici sans huitième réglage. Le dépassement possible est borné à **une**
itération et reste décompté par le quota — c'est-à-dire le comportement d'avant cette subfeature,
pour une itération au lieu de trente.

### D-L8-4 — Rien n'est affiché en dollars

F-36 affiche « Plafond de dépense de ce run atteint » **sans montant** : le produit parle à
l'utilisateur en tokens (quota, rapport d'usage, ligne vivante), et les dollars sont un réglage
commercial serveur. Introduire ici un montant en dollars ouvrirait une surface de prix côté client
— comparaisons avec l'abonnement, arrondis, devise — pour zéro gain sur le geste. « Affiché » est
donc tenu dans l'unité du produit : `m:ss · N tokens`, et la bande de plafond.

### D-L8-5 — `budgetReached` ne vaut **que** pour le plafond de consommation

Le budget de **temps** (10 min) existe depuis F-38 et dit déjà sa cause dans le texte de réponse.
Les confondre sous un même drapeau ferait apparaître « Racheter des tokens » sur un tour arrêté par
la montre — un conseil faux. Deux causes, deux textes, un seul drapeau, celui de la dépense.

### D-L8-6 — Le tour de la boucle maison écrit désormais `terminal_json`

La colonne existe (F-30 / SF-30-09) et porte exactement la forme attendue par l'écran ; la boucle
maison ne la renseignait pas. Elle y écrit sa consommation, sa durée et ses drapeaux — **avec une
liste de blocs vide** : persister les blocs est un autre sujet (bornage de transcription, SF-30-09),
qui n'appartient pas à ce lot. Effet immédiat : au rechargement, un tour arrêté sur le plafond
**dit encore pourquoi**, ce qui est exactement le moment où l'on se pose la question.
*Corollaire* : `toThreadItem` relit le coût même quand les blocs sont vides — il ne le faisait pas,
et un tour mesuré mais sans blocs perdait sa mesure.

### D-L8-8 — La bande de plafond dit « ce message », plus « ce run »

F-36 avait écrit « plafond de dépense de ce **run** atteint », et promettait de reprendre « dans la
**même sandbox** ». Les deux mots appartenaient au chemin Managed Agents. Depuis le lot 4 il n'y a
qu'un terminal : « run » est un mot d'implémentation que l'utilisateur n'a jamais vu ailleurs dans le
produit, et sur sa machine il n'y a **pas** de sandbox — l'y envoyer chercher une explication serait
un faux repère. La bande dit donc « ce **message** » des deux côtés, et adapte sa seule phrase de
reprise au moteur. Le reste — travail conservé, relancer débloque, rachat en second recours — est
inchangé. **Réversible** (deux chaînes).

### D-L8-7 — Emplacement de la mini-spec

Le lot est adressé sous l'étiquette d'orchestration `F-39-lot8`. La mini-spec est rangée en
`docs/features/F-39/`, avec les douze autres subfeatures du chantier, conformément à `CLAUDE.md`
(« `docs/features/F-XX/SF-XX-YY-nom.md` ») : `F-39-lot8` nomme un **lot de livraison**, pas une
feature du `PRODUCT_SPEC`. Créer un répertoire de feature homonyme fragmenterait la documentation du
chantier. **Réversible** (un `git mv`).
