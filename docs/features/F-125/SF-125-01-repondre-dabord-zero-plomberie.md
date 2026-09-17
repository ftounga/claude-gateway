# Mini-spec — [F-125 / SF-125-01] Répondre d'abord, zéro plomberie dans la réponse

## Identifiant

`F-125 / SF-125-01`

## Feature parente

`F-125` — La tenue de la carte du poste : silencieuse, robuste, jamais dans la réponse

## Statut

`ready`

## Date de création

2026-09-17

## Branche Git

`feat/SF-125-01-repondre-dabord`

---

## Objectif

> En une phrase : la réponse rendue à l'utilisateur traite **sa** question, en clair, et ne contient
> **jamais** la plomberie de tenue de carte (promotion, destinations, gouvernance, marqueurs).

---

## Comportement attendu

### Cas nominal

1. **Prompt (`AtelierChatService.buildSystemPrompt`, cibles RUNNER + SANDBOX)** : une consigne non
   négociable est ajoutée en tête du préfixe stable (aux côtés de la discipline d'investigation
   SF-119-02, de la doctrine SF-120-01 et du style SF-121-03) : *réponds d'abord à la question de
   l'utilisateur, en clair* ; *la tenue de la carte (promotion, rangement, gouvernance) est un travail
   de coulisse qui ne se raconte jamais dans la réponse* ; *n'emploie pas dans la réponse les termes de
   plomberie* (« promotion », « fin-de-tour », « hors gouvernance », « libellé », une fiche comme
   « destination »). Placée dans le préfixe stable → survit à `SYSTEM_MAX_CHARS`, cache préservé.
2. **Rendu backend** : la réponse **persistée et rendue** à l'utilisateur est débarrassée de tout
   marqueur de métadonnées `<!-- fin-de-tour: … -->` (et variantes). Le marqueur est retiré **après**
   que les contrôles de fin de tour l'ont lu (ils lisent `finalText` inchangé) et **avant** la
   persistance (`AtelierMessage.content`) et le renvoi (`AtelierChatResult.reply`). Le rejeu vers le
   modèle en cas de blocage garde le marqueur (le modèle doit voir ce qu'il a écrit).
3. **Rendu frontend** : `renderMarkdown` (point de passage unique du pipe `markdown`, utilisé par le
   terminal de l'atelier, le chat et le widget d'aide) retire les commentaires-marqueurs `fin-de-tour`
   avant le rendu — défense en profondeur, y compris pour les messages historiques déjà persistés.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|----------------------|
| La réponse du modèle **est uniquement** un marqueur (rien d'autre) | Après strip, le contenu est vide → repli `EMPTY_REPLY_FALLBACK` (jamais de message vide persisté, contrat SF-28-18) |
| Marqueur **tronqué** sans `-->` fermant | Le strip cible `<!-- fin-de-tour … -->` fermé ; un fragment sans fermeture n'est pas un commentaire HTML rendu (il reste texte) — non régressif, traité au fond par SF-125-02 |
| Contenu `null`/vide au rendu frontend | `renderMarkdown` rend `''` (comportement existant conservé) |
| Marqueur présent **dans un bloc de code** utilisateur | Le strip cible la chaîne `fin-de-tour` : un `<!-- fin-de-tour -->` volontaire dans du code est extrêmement improbable ; risque assumé et documenté (on ne retire que ce marqueur précis, pas tout commentaire HTML) |

---

## Critères d'acceptation

- [ ] Le prompt porte la consigne « répondre d'abord / carte en coulisse / pas de termes de plomberie »
      sur la cible **SANDBOX** (substrings vérifiés).
- [ ] La même consigne est présente sur la cible **RUNNER** (substrings vérifiés).
- [ ] La consigne **coexiste** avec la discipline (SF-119-02), la doctrine (SF-120-01) et le style
      (SF-121-03) — non-régression.
- [ ] Un contenu assistant contenant `<!-- fin-de-tour: … -->` est **rendu sans le marqueur** côté
      frontend (`renderMarkdown`).
- [ ] La réponse **persistée** (`AtelierMessage.content`) et **renvoyée** (`AtelierChatResult.reply`)
      ne contient plus le marqueur, alors que le contrôle de fin de tour l'a bien lu.
- [ ] Une réponse réduite au seul marqueur retombe sur le repli de réponse vide (pas de contenu vide).

## Plan de test minimal

- **Unitaires backend** :
  - `AtelierChatServiceSystemPromptTest` : consigne présente sur SANDBOX et sur RUNNER + coexistence.
  - `AtelierChatServiceTest` (ou dédié) : `stripTurnMetadata` retire le marqueur ; une réponse
    marqueur-seul → repli.
- **Unitaires frontend** :
  - `markdown.pipe.spec.ts` : `renderMarkdown('texte\n<!-- fin-de-tour: … -->')` ne contient pas
    `fin-de-tour`.
- **Isolation utilisateur** : sans objet — aucune donnée multi-tenant nouvelle, aucun endpoint, aucune
  requête. La consigne et le strip sont indépendants du `user_id` (le tour porte déjà son couple
  `(userId, workspaceId)` vérifié en amont).

## Tables / endpoints / composants impactés

- **Backend** : `AtelierChatService` (constante de consigne + `buildSystemPrompt` + `stripTurnMetadata`
  appliqué à la réponse finale). Aucune table, aucune migration, aucun endpoint.
- **Frontend** : `shared/markdown.pipe.ts` (`renderMarkdown`). Aucun composant modifié (point de
  passage unique du pipe).
- **Contrôles de fin de tour** : **non modifiés** (ils lisent toujours le `finalText` avec marqueur).

## Analyse transversale (préoccupations)

- **Auth / Principal** : non concernée.
- **Contexte tenant** : non concernée (aucun nouvel accès données).
- **Plans / limites** : non concernée.
- **Navigation / routing** : non concernée. Le pipe `markdown` est partagé — un strip trop large
  pourrait masquer du contenu ; il est **borné au marqueur `fin-de-tour`** pour ne rien retirer d'autre.
  Emplois du pipe recensés : `atelier-terminal.component.html` (`message.content`, `live.text`),
  `chat.component.html`, `help-chat-widget.component.html`, `copy-block`. Tous passent par
  `renderMarkdown` → un seul point à corriger, un seul test.

## Hors périmètre (explicite)

- La **robustesse** du marqueur (virgules, troncature) et le suivi côté serveur → **SF-125-02**.
- Le fichier de carte non déclaré → **SF-125-03**.
- Le desserrage du crochet de fin de tour (dette non bloquante) → **SF-125-04**.
- On ne touche **ni au fond de la cartographie ni au raisonnement** (F-119/F-120).
