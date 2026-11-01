# Mini-spec — F-232 / SF-232-03 — Les annonces téléphone verrouillé (D1b)

## Identifiant

`F-232 / SF-232-03`

## Feature parente

`F-232` — Le compagnon Android de Jarvis (cadrage `CADRAGE-F-232-compagnon-android.md`, D1b, D1c, §Auth corrigée le 2026-11-01)

## Statut

`in-review`

## Date de création

2026-11-01

## Branche Git

`feat/SF-232-03-annonces-verrouille`

---

## Objectif

> Quand un tour lancé via Jarvis se termine ou qu'une alerte de veille arrive, le compagnon Android **appairé**
> la dit à voix haute, téléphone verrouillé, puis écoute quelques secondes la réponse sans mot d'éveil.

---

## Comportement attendu

### Appairage (même geste que l'appairage d'un poste)

| Route | Chaîne | Rend |
|---|---|---|
| `POST /jarvis/companions/pairing-code` | utilisateur (JWT) | `{ code, expiresAt }` — 8 caractères, 5 min, usage unique, stocké haché |
| `GET /jarvis/companions` | utilisateur (JWT) | les compagnons appairés et non révoqués : `{ id, label, pairedAt, lastSeenAt }` |
| `DELETE /jarvis/companions/{id}` | utilisateur (JWT) | 204 ; le jeton est refusé dès la requête suivante ; 404 chez autrui |
| `POST /runner/jarvis/pair` | runner (public, code dans le corps) | `{ token }` — **jeton dédié**, rendu une seule fois, stocké haché |
| `GET /runner/jarvis/announcements?cursor=` | runner (en-tête `X-Jarvis-Token`, vérifié par le contrôleur) | `{ cursor, items: [{ id, kind, text, createdAt }] }` |

- Le jeton du compagnon **n'ouvre que** `GET /runner/jarvis/announcements` : il vit dans sa propre table, est lu par
  son propre en-tête, n'est jamais posé dans le `SecurityContext`. Un jeton runner n'y est pas accepté, et le jeton
  du compagnon n'est accepté par aucune autre route (chaîne principale JWT, routes runner `X-Runner-Token`).
- Échéance **glissante** de 60 jours (comme F-194) : chaque lecture la repousse ; au-delà, 401 et réappairage.
- Au plus **5** compagnons actifs par compte (409 `jarvis_companion_limit`).

### Annonces

- Sources, filtrées par `user_id` du jeton : réponses `ASSISTANT` d'un tour **via Jarvis**
  (`atelier_messages.via = 'JARVIS'`) → « *Sujet* : *phrase orale* » (`JarvisReplies.oralLine`) ; alertes de veille
  (`jarvis_watch_alerts`) → « Veille : *phrase* ».
- Sans `cursor` (premier appel) : aucune annonce, `cursor = maintenant` — on ne relit jamais le passé.
- Avec `cursor` : les annonces créées après `cursor − 2 min` (recouvrement contre les validations tardives ; le
  compagnon déduplique par `id`), **jamais plus vieilles que 10 min** (un téléphone resté hors ligne ne déverse pas
  sa matinée), au plus 5, de la plus ancienne à la plus récente.
- Côté compagnon, toutes les 20 s quand il est appairé et que l'écoute est en marche (pas sous le seuil batterie) :
  - **téléphone verrouillé ou écran éteint**, et l'écran Jarvis non ouvert par lui : il **dit** chaque annonce
    (`TextToSpeech`, usage « assistant ») puis écoute **8 s** : le premier mot entendu (n'importe lequel) ouvre
    Jarvis comme le mot d'éveil (Jarvis écoute aussitôt, SF-232-02) ;
  - téléphone déverrouillé en main : il ne parle pas (l'écran fait foi) ; l'annonce est consommée.

### Écran Jarvis

- Section « Annonces téléphone verrouillé » : [Appairer le compagnon] affiche le code et son échéance ; la liste des
  compagnons appairés (nom, dernière lecture) avec [Révoquer] confirmé en ligne (pas de `window.confirm`).

### Cas d'erreur

| Situation | Comportement attendu | Code |
|-----------|---------------------|------|
| Code inconnu, expiré ou déjà consommé | `jarvis_companion_pairing_invalid` (générique, comme `/runner/pair`) | 401 |
| Jeton absent, inconnu, révoqué ou expiré | 401 générique (ne dit pas lequel) | 401 |
| Révoquer le compagnon d'autrui | introuvable | 404 |
| 6ᵉ compagnon | `jarvis_companion_limit` | 409 |
| Gateway injoignable (compagnon) | nouvel essai au tour suivant ; jamais d'erreur dite à voix haute | — |
| 401 reçu par le compagnon | il oublie le jeton et affiche « Réappairer » | — |

---

## Critères d'acceptation

- [ ] Un code généré dans l'écran Jarvis s'échange une fois contre un jeton ; une 2ᵉ fois → 401.
- [ ] Le jeton lit les annonces d'Alice ; jamais celles de Bob (isolation `user_id`).
- [ ] Le jeton du compagnon est refusé ailleurs : `X-Runner-Token` d'une route runner, `Authorization: Bearer` de la chaîne principale.
- [ ] Un jeton runner est refusé par `/runner/jarvis/announcements`.
- [ ] Révoqué → 401 immédiat ; absent de la liste.
- [ ] Premier appel sans curseur → aucune annonce ; un tour via Jarvis puis une alerte → deux annonces dans l'ordre ; un tour tapé → rien.
- [ ] Annonce plus vieille que 10 min → jamais rendue.
- [ ] Le compagnon ne parle que téléphone verrouillé / écran éteint, ne répète jamais une annonce (déduplication par `id`), écoute 8 s après.
- [ ] Le jeton n'est jamais journalisé ; il est stocké haché côté gateway.
- [ ] Design system : jetons `--cg-*`, Angular Material.

---

## Contraintes de validation

| Champ | Règle |
|---|---|
| `code` | 1..32 caractères ; casse et espaces ignorés |
| `label` | 0..80 caractères, défaut « Téléphone Android » |
| `cursor` | ISO-8601 ; illisible → traité comme absent |

---

## Plan de test minimal

- **Unitaires backend** (`JarvisCompanionServiceTest`) : fenêtre (recouvrement, plancher 10 min, plafond 5), échéance glissante, limite de 5.
- **Intégration** (`JarvisCompanionApiIntegrationTest`) : appairage complet, code à usage unique, annonces Alice / pas Bob, tour tapé ignoré, alerte, révocation → 401, jeton runner refusé, jeton compagnon refusé par la chaîne principale et par une route runner.
- **Frontend** (`jarvis-companions.component.spec.ts`) : code affiché, liste, révocation confirmée.
- **Android** (`test-core.sh`) : `AnnouncementPolicy` — parler seulement verrouillé, déduplication bornée, fenêtre d'écoute.
- **Isolation utilisateur** : tests d'intégration ci-dessus (annonces, révocation chez autrui → 404).

---

## Impacts

- **Tables** : `jarvis_companions` (nouvelle, migration 194) ; index `(user_id, via, created_at)` sur `atelier_messages` (lecture des annonces toutes les 20 s).
- **Endpoints** : cinq routes ci-dessus. Chaîne runner : deux entrées déclarées une par une.
- **Frontend** : `JarvisCompanionsComponent` (nouveau) dans l'écran Jarvis, `JarvisService` (3 appels), modèles.
- **Android** : permission `INTERNET` (ajoutée — imposée par l'appairage du cadrage corrigé), `Prefs` (jeton, curseur), `AnnouncementClient`, `CompanionService` (relève, voix, écoute courte), `MainActivity` (étape « 5. Les annonces » : code d'appairage), `core/AnnouncementPolicy`.
- **Préoccupations transversales** :
  - [x] **Auth** — nouveau jeton dédié. Composants vérifiés : `RunnerSecurityConfig` (deux routes publiques nommées, authentification au contrôleur, rien dans le `SecurityContext`), `RunnerTokenAuthenticator` (ne lit pas `jarvis_companions` ; inchangé), chaîne principale `SecurityConfig` / filtre JWT (ne lit que `Authorization` ; inchangés), contrôleurs `X-Runner-Token` (inchangés). Tests de non-régression : jeton compagnon refusé par la chaîne principale et par une route runner.
  - [x] **Contexte tenant** — le `user_id` des annonces vient **exclusivement** de la ligne du jeton ; `CurrentUser` pour les routes utilisateur. Composants : `JarvisCompanionService`, `JarvisAnnouncementService`.
  - [ ] Plans / limites — sans objet (aucun appel modèle).
  - [x] **Navigation** — aucune route Angular nouvelle ; la section vit dans `/jarvis`. Lien profond inchangé.

---

## Hors périmètre

- La reconnaissance de la réponse par le compagnon lui-même (il ouvre Jarvis, qui écoute — la parole reste dans la PWA).
- Les annonces sur iPhone ; Web Push (F-153) inchangé.
- La lecture en flux et l'interruption (F-233).
- Compiler et essayer l'APK (pas de SDK Android ici ; à faire sur le téléphone du PO).
