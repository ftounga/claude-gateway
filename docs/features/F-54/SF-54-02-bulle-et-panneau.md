# Mini-spec — F-54 / SF-54-02 — La bulle d'aide et son panneau

## Identifiant

`F-54 / SF-54-02`

## Feature parente

`F-54` — Chatbot d'aide produit

## Statut

`ready`

## Date de création

2026-09-10

## Branche Git

`feat/SF-54-02-bulle-aide`

---

## Objectif

> Poser dans l'application une **bulle d'aide toujours accessible** : un clic ouvre un panneau, on
> pose sa question, la réponse arrive — et l'endroit où demander existe enfin.

---

## Comportement attendu

### Cas nominal

1. Sur toute page de la **zone authentifiée**, une bulle ronde flotte en bas à droite.
2. Un clic ouvre un panneau au-dessus d'elle. L'icône de la bulle devient une croix.
3. Le panneau propose **trois questions suggérées**, tirées des obstacles réels de mise en service.
   Un clic sur l'une d'elles la pose immédiatement.
4. Une zone de saisie multiligne, avec un **compteur `n/500`** ; `Entrée` envoie, `Maj+Entrée` va à
   la ligne.
5. Pendant l'appel : un indicateur d'attente, la saisie et les suggestions désactivées.
6. La réponse s'affiche en Markdown **assaini** (pipe `markdown` existant), et la saisie se vide.
7. Une note permanente rappelle que l'aide porte sur le produit, jamais sur le contenu des projets.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Question vide ou uniquement des espaces | Le bouton d'envoi est **désactivé** ; `Entrée` ne fait rien |
| Question > 500 caractères | Compteur en rouge, bouton **désactivé**, aucun appel émis |
| Appel en échec (429, 502, 503, réseau) | Le **message du backend** est affiché dans un bloc d'erreur ; à défaut, un message générique |
| Utilisateur non connecté | La bulle **n'existe pas** dans le DOM |
| Un nouvel envoi après une erreur | L'erreur précédente est effacée avant l'appel |

---

## Critères d'acceptation

- [ ] La bulle est présente sur les pages de la zone authentifiée, et **absente** hors de celle-ci
- [ ] Un clic ouvre le panneau, un second le ferme ; le bouton de fermeture du panneau le ferme aussi
- [ ] Les trois suggestions sont affichées et un clic sur l'une d'elles déclenche l'appel
- [ ] Le compteur affiche `n/500` et passe en état d'erreur au-delà de 500
- [ ] Le bouton d'envoi est désactivé si la question est vide, trop longue, ou pendant un appel
- [ ] `Entrée` envoie ; `Maj+Entrée` insère un saut de ligne
- [ ] Pendant l'appel, un indicateur d'attente est visible
- [ ] La réponse est affichée en Markdown assaini
- [ ] Une erreur affiche le message du backend, et n'efface pas la question saisie
- [ ] Aucune couleur, police ou espacement hors `docs/DESIGN_SYSTEM.md`

---

## Périmètre

### Hors scope (explicite)

- Historique des échanges dans le panneau : une question, une réponse, puis la suivante remplace.
- Streaming de la réponse.
- Copie de la réponse, retour d'appréciation, ouverture d'un ticket.
- Toute question portant sur le contenu des projets : c'est l'Atelier.
- Version mobile dédiée (le panneau s'adapte simplement en largeur).

---

## Valeurs initiales

| Réglage | Valeur |
|---|---|
| Longueur maximale | **500** caractères (miroir de la contrainte serveur) |
| Suggestions | « Comment connecter ma machine ? », « Quel fichier télécharger si je n'ai pas Java ? », « Mon terminal ne sort pas : que vérifier ? » |
| Largeur du panneau | 360 px, réduite à la largeur disponible sous 420 px |

---

## Contraintes de validation

| Champ | Règle | Effet |
|---|---|---|
| Question | non vide après suppression des espaces | bouton d'envoi désactivé sinon |
| Question | ≤ 500 caractères | compteur en erreur et bouton désactivé au-delà |

Le client **borne**, il ne remplace pas : le serveur revalide (SF-54-01).

---

## Technique

### Endpoint(s) consommé(s)

| Méthode | URL | Auth |
|---------|-----|------|
| POST | `/api/help/chat` | JWT porté par l'intercepteur existant |

### Composants Angular

| Composant | Rôle |
|---|---|
| `HelpService` (`core/services/help.service.ts`) | Appel `POST /api/help/chat` |
| `HelpChatWidgetComponent` (`help/help-chat-widget/`) | Bulle + panneau, états, suggestions, compteur |
| `ShellComponent` | **Hôte** de la bulle |

### Tables impactées

Aucune.

### Migration Liquibase

- [x] Non applicable.

---

## Plan de test

### Tests unitaires — `HelpChatWidgetComponent`

- [ ] U-01 : le panneau est fermé à l'ouverture, `togglePanel` l'ouvre puis le ferme
- [ ] U-02 : trois suggestions sont exposées, et cliquer sur l'une d'elles appelle le service avec son texte
- [ ] U-03 : envoi impossible si la question est vide, trop longue, ou pendant un appel
- [ ] U-04 : un envoi réussi affiche la réponse et vide la saisie
- [ ] U-05 : un envoi en échec affiche le message du backend et **conserve** la question
- [ ] U-06 : un nouvel envoi efface l'erreur et la réponse précédentes
- [ ] U-07 : le compteur reflète la longueur saisie et signale le dépassement

### Tests unitaires — `HelpService`

- [ ] U-08 : `chat()` émet un `POST /api/help/chat` avec `{message}` et rend `{answer}`

### Tests d'intégration — `ShellComponent`

- [ ] IT-01 : la coquille authentifiée rend le composant de bulle d'aide

### Isolation `user_id`

Sans objet côté client : aucune donnée utilisateur n'est transmise ni affichée. Le JWT porte
l'identité, comme sur tous les autres appels ; l'isolation est garantie côté serveur (SF-54-01).

---

## Analyse d'impact

### Préoccupations transversales touchées

- [ ] Auth / Principal — **non** : aucun changement d'authentification ; la bulle vit dans la
  coquille qui est déjà derrière `authGuard`
- [ ] Contexte tenant — **non**
- [ ] Plans / limites — **non** : l'aide ne consomme pas le quota (SF-54-01, D3)
- [x] **Navigation / routing** — *touchée à la marge* : **aucune route n'est ajoutée ni modifiée**.
  Le seul changement est l'ajout d'un composant dans le gabarit de `ShellComponent`.
  Composants impactés, vérifiés : `ShellComponent` (hôte, test mis à jour) ; les pages hors coquille
  (`landing`, `login`, `register`, `auth/*`, pages légales, `onboarding`) n'affichent **pas** la
  bulle, ce qui est le comportement voulu — l'aide est réservée aux comptes connectés.

---

## Dépendances

### Subfeatures bloquantes

`SF-54-01` — **livrée** (PR #308).

### Questions ouvertes impactées

Aucune.

---

## Notes et décisions

| # | Décision | Motif | Alternative écartée | Réversible |
|---|---|---|---|---|
| **D1** | La bulle est portée par **`ShellComponent`**, pas par `AppComponent` | La coquille **est** la zone authentifiée du produit : y poser la bulle rend la condition « réservée aux comptes connectés » structurelle, au lieu d'un test d'authentification recopié dans un gabarit | La poser dans `AppComponent` avec un `@if (currentUser())` (F-104 de legalcase) : la bulle apparaîtrait alors aussi sur l'onboarding, qui est un parcours qu'on ne veut pas encombrer | Oui |
| **D2** | Une seule réponse affichée, sans fil de discussion | Le service est sans mémoire (SF-54-01) : afficher un fil laisserait croire que le contexte est conservé | Fil d'échanges côté client | Oui |
| **D3** | Réponse rendue en **Markdown assaini** via le pipe existant | Les réponses contiennent des commandes et des listes ; les afficher en texte brut les rendrait illisibles. Le pipe passe déjà par DOMPurify | Texte brut | Oui |
| **D4** | Suggestions tirées des **obstacles réels** de la mise en service | Ce sont les questions qui ont réellement coûté deux jours ; une suggestion générique n'apprend rien | Suggestions génériques | Oui |
