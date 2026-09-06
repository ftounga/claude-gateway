# Mini-spec — F-39 / SF-39-19 — Parler pendant qu'il travaille

## Identifiant

`F-39 / SF-39-19`

## Feature parente

`F-39` — L'Atelier comme harnais

## Statut

`ready`

## Date de création

2026-09-06

## Branche Git

`feat/SF-39-19-parler-pendant-le-travail`

---

## Objectif

> Pouvoir envoyer un message **pendant** qu'un tour travaille, et que l'agent le lise à l'étape
> suivante — au lieu d'attendre dix minutes pour lui dire ce qu'on voit déjà.

---

## Déclencheur

Banc d'essai : *« j'ai aussi remarqué que pendant que le terminal est en cours il n'accepte aucune
autre question. Pourtant Claude Code le permet. »*

Vérifié : le champ de saisie est désactivé tant que le tour tourne
(`atelier-terminal.component.html`, `[disabled]="submitting"`). Sur un tour de trente étapes qui dure
dix minutes, on regarde l'agent partir dans une direction sans pouvoir le lui dire.

C'est exactement ainsi que cette session s'est déroulée, du côté Claude Code : chaque remarque du
product owner est arrivée **à l'intérieur** d'un tour en cours, et a changé la suite du travail sans
l'interrompre. C'est cette capacité-là qui manque à l'Atelier.

---

## Ce que ce n'est pas

**Ce n'est pas une interruption.** F-32 et SF-38-07 arrêtent le tour à sa frontière sûre. Ici, on
n'arrête rien : on **ajoute au contexte**, et l'agent continue en tenant compte de ce qu'on vient de
dire. Les deux gestes coexistent, et l'écran doit les distinguer.

---

## Comportement attendu

### Cas nominal

1. Pendant qu'un tour travaille, le champ de saisie reste **actif**.
2. Envoyer un message le **dépose** pour le tour en cours — il n'ouvre pas un second tour.
3. Il apparaît immédiatement dans le fil, à sa place chronologique, signalé comme une précision
   adressée au travail en cours.
4. La boucle le consulte **au début de l'itération suivante**, à la même frontière sûre où elle
   regarde déjà l'interruption et le budget, et l'ajoute à la conversation avant l'appel.
5. L'agent le lit donc à l'étape d'après : il peut corriger le tir, préciser, rediriger.
6. Le message est **consommé** : il n'est ajouté qu'une fois.

### Aucun tour en cours

Le message est traité comme un message normal — il ouvre un tour. C'est le comportement d'aujourd'hui,
et l'écran n'a pas à savoir lequel des deux se produit.

### Bornes

| Borne | Valeur | Pourquoi |
|---|---|---|
| Longueur d'un message | 4 000 caractères | Une précision, pas une nouvelle consigne |
| Messages en attente par tour | 5 | Au-delà, c'est un nouveau tour qu'il faut, pas des rustines |
| Portée | le **tour** | Effacée à l'ouverture du suivant, comme la marque d'interruption |

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Message vide ou blanc | Refus, rien n'est déposé | 400 |
| Plus de 5 messages en attente | Refus lisible : « trop de précisions en attente pour ce message » | 409 |
| Projet non possédé | 404, **avant** toute autre vérification | 404 |
| Aucun tour en cours | Le message ouvre un tour normal (chemin existant) | 200 |
| Le tour se termine avant consommation | Les messages non lus sont **abandonnés** avec le tour, pas rejoués au suivant |  |

**Aucun dépôt ne peut faire échouer le tour en cours.**

---

## Critères d'acceptation

- [ ] Le champ de saisie reste actif pendant un tour.
- [ ] Un message envoyé pendant un tour est **déposé**, pas transformé en second tour.
- [ ] La boucle le consulte au début de l'itération suivante et l'ajoute à la conversation.
- [ ] Il n'est ajouté **qu'une fois** (consommé).
- [ ] Au plus 5 en attente ; le sixième est refusé avec un motif lisible.
- [ ] Un message de plus de 4 000 caractères est refusé.
- [ ] La marque ne survit pas au tour.
- [ ] Le dépôt est **diffusé aux pods pairs** : la boucle tourne peut-être ailleurs que là où le
      message atterrit.
- [ ] Le registre est clefé `userId:workspaceId` et vit **hors** de tout champ d'instance partagé.
- [ ] Isolation : `requireOwned` premier geste.
- [ ] Zéro régression : sans tour en cours, l'envoi se comporte comme aujourd'hui.

---

## Périmètre

### Hors scope

- La reprise d'un flux SSE interrompu (`Last-Event-ID`) — chantier à part, déjà noté en SF-39-18.
- Le chemin **Managed Agents** : le fournisseur tient sa propre boucle, y injecter un message
  demande son propre mécanisme. Cette subfeature ne touche que la boucle maison.

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Normalisation |
|-------|-------------|-------------|---------------|
| `message` | Oui | 4 000 | `trim()` ; vide ⇒ 400 |

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Notes |
|---------|-----|------|-------|
| POST | `/api/workspaces/{id}/chat/steer` | Oui | Corps : `{ "message": "…" }` |

### Tables impactées

Aucune. Le message rejoint la conversation du tour, et l'historique par le chemin habituel.

### Migration Liquibase

- [x] **Non applicable.**

### Classes impactées

| Classe | Changement |
|--------|-----------|
| `atelier/AtelierChatService` | Registre des messages en attente ; consommation en tête d'itération |
| `atelier/AtelierChatController` | Endpoint de dépôt |
| `runner/relay/*` | Diffusion aux pairs, par le chemin de l'interruption |
| `atelier-terminal` (écran) | Champ actif pendant un tour ; message affiché dans le fil |

---

## Préoccupations transversales

| Préoccupation | Concernée | Composants impactés |
|--------------|-----------|--------------------|
| Auth / Principal | Non | — |
| **Contexte tenant** | **Oui** | Registre clefé `userId:workspaceId`, jamais le seul workspace — même règle que la marque d'interruption et que le plan (SF-39-13), où un champ d'instance aurait fait fuiter l'état d'un utilisateur chez un autre. `requireOwned` premier geste du dépôt. |
| **Plans / limites** | **Oui** | Le message rejoint la conversation : il compte donc dans les tokens d'entrée de l'itération suivante, et à ce titre dans le quota (F-10) et le plafond par message (SF-39-15). C'est ce que bornent les 4 000 caractères × 5 — au plus 20 000 caractères, négligeable devant le seuil d'écartement de contexte. Aucun appel aux services de limites n'est ajouté. |
| **Navigation / routing** | Non | Aucune route touchée |

---

## Plan de test

### Tests unitaires

- [ ] Un message déposé est ajouté à la conversation à l'itération suivante.
- [ ] Il n'est ajouté qu'une fois.
- [ ] Plusieurs messages sont ajoutés dans l'ordre de dépôt.
- [ ] Le sixième dépôt est refusé.
- [ ] Un message vide est refusé.
- [ ] La marque ne survit pas au tour.
- [ ] Non-régression : un tour sans dépôt est inchangé.

### Tests d'intégration

- [ ] `POST /chat/steer` → 204 sur un projet possédé, 404 sur celui d'un autre.

### Tests frontend

- [ ] Le champ reste actif pendant un tour, et l'envoi appelle `steer` plutôt que `stream`.

### Isolation workspace

- [x] Applicable — test à deux utilisateurs sur l'endpoint.

---

## Notes et décisions

**D1 — Déposer, pas interrompre.** L'interruption existe déjà et arrête le travail. Ici on l'enrichit.
Confondre les deux gestes dans un seul bouton ferait perdre celui qu'on utilise le plus souvent :
préciser sans casser.

**D2 — Consommé au début de l'itération, jamais au milieu.** La boucle a des frontières sûres — c'est
là qu'elle regarde l'interruption, le budget de temps et le plafond de dépense. Injecter un message
ailleurs qu'à cet endroit reviendrait à modifier une conversation pendant qu'elle part au fournisseur.

**D3 — Les messages non lus meurent avec le tour.** Les rejouer au tour suivant ferait resurgir une
précision devenue sans objet, dans un contexte qui a changé. Ce qui n'a pas été lu à temps ne
l'était plus.

**D4 — Le registre vit dans le tour, jamais dans un champ du service.** `AtelierChatService` est un
singleton partagé par tous les utilisateurs. Le plan de SF-39-13 a failli fuiter d'un utilisateur à
l'autre pour cette raison exacte, et la clef composite est la même parade.
