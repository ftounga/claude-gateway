# Mini-spec — F-232 / SF-232-07 — L'écran `/jarvis` dans le compagnon

## Identifiant

`F-232 / SF-232-07`

## Feature parente

`F-232` — Le compagnon Android de Jarvis (cadrage `CADRAGE-F-232-compagnon-android.md`, **amendement du 2026-11-04**, D6, D7, D8, D10)

## Statut

`in-review`

## Date de création

2026-11-04

## Branche Git

`feat/SF-232-07-ecran-jarvis-compagnon`

---

## Objectif

> Ouvert dans la WebView du compagnon, l'écran `/jarvis` prend sa session du pont natif, écoute aussitôt la commande,
> demande le déverrouillage avant un « oui » engageant et se ferme à la fin de la conversation ; hors compagnon, rien
> ne change.

---

## Comportement attendu

### Cas nominal

1. **Le pont** (`CompanionBridgeService`) : présent seulement si `window.JarvisCompanion` existe (WebView du
   compagnon). Méthodes natives sans donnée rendue : `requestSession(purpose)`, `isLocked()`, `requestUnlock()`,
   `keepAlive()`, `close()`. Retours du natif : `window.jarvisCompanionSession(session | null)`,
   `window.jarvisCompanionUnlocked(ok)`.
2. **Session** (`AuthService`) : dans le compagnon, aucun jeton n'est lu ni écrit dans le `localStorage` ; la session
   remise par le pont vit en mémoire et est renouvelée 5 min avant son échéance (`RENEW`).
   - `authGuard` : sans session, la demande au pont (`OPEN`) ; refusée ⇒ l'écran du compagnon se ferme (jamais
     `/login`).
   - Intercepteur : un 401 redemande une session (`RENEW`) au lieu d'aller à `/login` ; refusée ⇒ fermeture. Un 403
     (`jarvis_companion_scope`) reste une erreur ordinaire.
3. **Écoute immédiate** (D6) : `?ecoute=1` (comme `?source=compagnon`) démarre la conversation mains libres à
   l'ouverture (réveil compté `WAKE`).
4. **Mot d'éveil du navigateur coupé** dans le compagnon (le natif l'assure ; le micro reste à la conversation).
5. **Signe de vie** : chaque changement d'état ou de mode de conversation appelle `keepAlive()`.
6. **Fin** : la mise en veille au silence (`IDLE_END`) dit « Je me mets en veille. Dites « Jarvis » pour reprendre. »
   puis **ferme** l'écran du compagnon.
7. **Verrouillé, « oui » engageant** (D8) : réponse « oui » nette à une demande « Vous confirmez ? » d'Opus, ou à une
   autorisation d'outil ⇒ Jarvis dit « Déverrouillez le téléphone pour confirmer. » et demande le déverrouillage
   (`requestUnlock`) ; réussi (le natif a d'abord remis une session déverrouillée), le « oui » part ; annulé ⇒
   « Rien n'est fait : il faut déverrouiller le téléphone pour confirmer. » et rien ne part. « Non » ou réponse
   ambiguë : inchangé (aucun déverrouillage).

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Pont absent (navigateur, PWA) | comportement actuel, session `localStorage` | — |
| Session refusée par le natif | l'écran du compagnon se ferme | — |
| Le natif ne répond pas | échec au bout de 15 s (session) / 65 s (déverrouillage) | — |
| 401 dans le compagnon | nouvelle session `RENEW`, jamais `/login` | 401 |
| 403 de portée | erreur ordinaire, pas de renouvellement | 403 |
| Déverrouillage annulé | rien ne part, phrase fixe | — |

---

## Critères d'acceptation

- [x] Hors compagnon : jeton du `localStorage`, `unlock()` vrai, `openCompanionSession` faux (aucun changement).
- [x] Dans le compagnon : session du pont, jamais lue ni écrite dans le `localStorage`, renouvelée avant échéance.
- [x] Garde : session demandée au pont ; refusée ⇒ fermeture, pas de `/login`.
- [x] 401 ⇒ `RENEW`, pas de `/login` ; 403 ⇒ rien.
- [x] `?ecoute=1` ⇒ Jarvis écoute aussitôt.
- [x] Verrouillé : « oui » à une autorisation d'outil ⇒ déverrouillage demandé ; annulé ⇒ rien accordé ; réussi ⇒ accordé ; « non » ⇒ refusé sans déverrouillage.
- [x] Design system : aucune nouvelle couleur ni police (aucun nouvel élément visuel).

---

## Contraintes de validation

| Champ | Règle |
|---|---|
| Attente du natif | session 15 s, déverrouillage 65 s |
| Renouvellement | 5 min avant échéance, jamais moins de 30 s après la remise |

---

## Plan de test minimal

- **Unitaires** : `companion-bridge.service.spec.ts` (9 cas : absent, session du pont sans stockage, refus, délai, renouvellement, déverrouillage réussi / annulé / déjà déverrouillé, signe de vie et fermeture).
- **Intégration (Angular)** : `auth.interceptor.companion.spec.ts` (garde OK / refus, 401 ⇒ RENEW, 403 inchangé) ; `jarvis.component.spec.ts` (+4 : verrouillé annulé, verrouillé réussi, « non » verrouillé, `ecoute=1`).
- **Non-régression** : suites `auth.guard`, `auth.interceptor`, `jarvis.component` existantes ; suite frontend complète.
- **Isolation utilisateur** : sans objet côté écran (la session porte le compte ; isolation testée par SF-232-05).

---

## Impacts

- **Tables / endpoints** : aucun.
- **Frontend** : `CompanionBridgeService` (nouveau), `AuthService`, `authGuard`, `authInterceptor`, `JarvisComponent`.
- **Préoccupations transversales** :
  - [x] **Auth / Principal** — source du jeton dans le compagnon. Composants vérifiés : `AuthService` (seul détenteur
    du jeton, lu par l'intercepteur et par les appels `fetch` : `AtelierService.streamChat`, `JarvisVoiceService`,
    `JarvisFastBrainClient`, `JarvisPieceClient` — tous lisent `auth.token()` à l'appel, donc la session renouvelée),
    `authGuard`, `authInterceptor`. Hors compagnon : chemins inchangés (tests existants verts).
  - [ ] Contexte tenant — sans objet.
  - [ ] Plans / limites — sans objet.
  - [x] **Navigation / routing** — `authGuard` (toutes les routes protégées) : dans le compagnon, plus de redirection
    vers `/login` ; hors compagnon, inchangé. Paramètre `?ecoute=1` sur `/jarvis` ; aucune route nouvelle.

---

## Hors périmètre

- L'activité Android, le pont natif et la demande de déverrouillage système (SF-232-08).
- Masquer les volets de réglage dans le compagnon (leurs écritures répondent 403, l'écran le dit comme une erreur).
