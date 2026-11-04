# Mini-spec — F-232 / SF-232-06 — Le garde-fou « téléphone verrouillé » côté passerelle

## Identifiant

`F-232 / SF-232-06`

## Feature parente

`F-232` — Le compagnon Android de Jarvis (cadrage `CADRAGE-F-232-compagnon-android.md`, **amendement du 2026-11-04**, D8)

## Statut

`in-review`

## Date de création

2026-11-04

## Branche Git

`feat/SF-232-06-garde-fou-verrouille`

---

## Objectif

> Téléphone verrouillé, la passerelle **n'exécute aucune action engageante** confirmée par « oui » ni aucune
> autorisation d'outil venant de la session du compagnon : l'action reste en attente jusqu'au « oui » d'une session
> déverrouillée. La règle est appliquée par le serveur, jamais confiée au modèle.

---

## Comportement attendu

### Cas nominal

1. **Origine du message.** Sur `POST /workspaces/{id}/chat` et `POST /workspaces/{id}/chat/stream`, le contrôleur lit
   sur le thread de requête `CurrentUser.companionSession()` (SF-232-05) et pose, pour le fil
   `(user_id, sujet, chantier)`, la marque **« téléphone verrouillé »** si la session est celle du compagnon avec
   `lk = true` ; toute autre requête (session ordinaire, compagnon déverrouillé) **efface** la marque. La marque vaut
   10 min au plus.
2. **Le « oui » des actions en attente** (`JarvisMailActionService`, `JarvisCalendarActionService`, appelés dans le
   fil Jarvis avec le message persisté de l'utilisateur) : marque posée **et** « oui » net ⇒ **rien n'est exécuté**,
   l'action reste `PENDING` (son échéance de 10 min court toujours) et une note fixe est jointe au message d'Opus :
   - courrier : `[Courrier — « oui » reçu téléphone verrouillé : rien n'est fait, l'action « … » reste en attente.
     Dis seulement qu'il faut déverrouiller le téléphone pour confirmer.]`
   - agenda : `[Agenda — « oui » reçu téléphone verrouillé : « … » n'est pas posé, l'action reste en attente. Dis
     seulement qu'il faut déverrouiller le téléphone pour confirmer.]`
   Couvre : envoyer, supprimer, archiver en lot des courriels ; poser un rendez-vous ; accepter, refuser une
   invitation. Une réponse autre que « oui » **annule** comme avant (rien d'irréversible).
3. **Autorisation d'outil** (`POST /workspaces/{id}/chat/confirm`) : session du compagnon verrouillée et décision
   « autoriser » ⇒ **423** `jarvis_phone_locked`, la demande reste ouverte (le refus, lui, passe).
4. Session déverrouillée (`lk = false`) ou session ordinaire : comportement **inchangé**.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| « oui » verrouillé à une action en attente | rien d'exécuté, action en attente, note fixe | 200 (flux) |
| « oui » verrouillé puis « oui » déverrouillé | l'action s'exécute une fois | 200 |
| « oui » verrouillé, action expirée | expirée comme avant (rien d'exécuté) | 200 |
| Autoriser un outil, verrouillé | `jarvis_phone_locked` | 423 |
| Refuser un outil, verrouillé | accepté (refus transmis) | 204 |

---

## Critères d'acceptation

- [x] Verrouillé, « oui » à un envoi de courriel en attente : rien n'est envoyé, l'action reste `PENDING`, la note le dit.
- [x] Puis « oui » déverrouillé : l'envoi part, une seule fois.
- [x] Même règle pour poser un rendez-vous / accepter une invitation.
- [x] Verrouillé, autre réponse que « oui » : annulation comme avant.
- [x] `chat/confirm` « autoriser » d'une session verrouillée ⇒ 423 ; « refuser » ⇒ transmis.
- [x] Une requête d'une session ordinaire efface la marque (le poste de travail n'est jamais bloqué).
- [x] Aucune consigne système ni outil du cerveau rapide / `JarvisOralNote` modifiés (préfixe stable ; porte des bancs non déclenchée).

---

## Contraintes de validation

| Champ | Règle |
|---|---|
| Marque « verrouillé » | par fil `(user_id, sujet, chantier)`, ≤ 10 min, effacée par toute requête non verrouillée |

---

## Plan de test minimal

- **Unitaires** : `JarvisMailActionServiceTest` / `JarvisCalendarActionServiceTest` (ou classes dédiées) — « oui » verrouillé ⇒ pas d'exécution, statut `PENDING`, note ; « oui » ensuite ⇒ exécution ; « non » verrouillé ⇒ annulation. `AtelierChatService` : marque posée / effacée / périmée.
- **Intégration** : `confirm` avec une session compagnon verrouillée ⇒ 423, déverrouillée ⇒ chemin normal (404/409 sans demande en attente, jamais 423).
- **Isolation utilisateur** : la marque est clefée par `user_id` ; les actions sont lues `findFirstByUserIdAndWorkspaceId…`.

---

## Impacts

- **Tables** : aucune.
- **Endpoints** : `POST /workspaces/{id}/chat`, `/chat/stream` (marque), `/chat/confirm` (423).
- **Backend** : `AtelierChatController`, `AtelierChatService` (marque, transmission au « oui »), `JarvisMailActionService`, `JarvisCalendarActionService`.
- **Préoccupations transversales** :
  - [x] **Auth / Principal** — lecture de `CurrentUser.companionSession()` (SF-232-05) sur le thread de requête, dans
    les trois endpoints ci-dessus, seuls à pouvoir engager une action. Autres endpoints de la portée : lecture,
    voix, mesure, préférences, portrait (interne) — vérifiés, sans action extérieure irréversible (relevé SF-232-05).
  - [x] **Contexte tenant** — marque clefée `user_id` ; actions filtrées `user_id`.
  - [ ] Plans / limites — sans objet.
  - [ ] Navigation — sans objet.

---

## Hors périmètre

- La demande de déverrouillage à l'écran (SF-232-07) et dans l'APK (SF-232-08).
- Les actions immédiates et réversibles des outils de courrier (brouillon, archivage unitaire) : inchangées.
