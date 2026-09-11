# Mini-spec — F-62 / SF-62-02 — « Payer, ou entrer un code » : la saisie sur l'écran du plan

## Identifiant

`F-62 / SF-62-02`

## Feature parente

`F-62` — Codes d'accès à durée limitée

## Statut

`done` — PR #353, mergée le 2026-09-10

## Date de création

2026-09-10

## Branche Git

`feat/SF-62-02-saisie-code-ecran-plan`

---

## Objectif

Sur l'écran du plan, à côté de « payer », offrir « ou entrer un code » — et, une fois le code saisi,
dire clairement ce qui est ouvert, jusqu'à quand, et ce qui **ne change pas**.

---

## Comportement attendu

### Cas nominal

1. L'écran `/billing` charge, en plus de ce qu'il charge déjà, le **droit offert** de l'utilisateur
   (`GET /api/access-code/grant`). Échec **non bloquant** : la section reste masquée, l'écran reste
   utilisable — même règle que les recharges et l'option Forge.
2. **Sans droit en cours**, une section « Vous avez un code d'accès ? » apparaît **sous les
   abonnements**, avec un champ et un bouton *Activer*. Elle dit en une phrase ce qu'un code fait :
   il ouvre la Forge pour 24 h, **sans changer l'offre ni le quota de tokens**.
3. À l'activation : `POST /api/access-code/redeem`. En cas de succès, la section est remplacée par un
   **bandeau d'accès offert** : « Accès Forge offert — jusqu'au 11 septembre 2026 à 14:32 », plus le
   rappel « Votre offre et votre quota de tokens n'ont pas changé », plus un lien vers la Forge.
4. Le bandeau disparaît de lui-même au prochain chargement passé le terme : l'écran ne calcule
   **jamais** l'expiration lui-même, il affiche ce que le serveur lui dit (`active`).

### Cas d'erreur

| Situation | Comportement attendu |
|---|---|
| Champ vide | Bouton *Activer* désactivé (aucun appel réseau). |
| `access_code_invalid` (404) | « Ce code d'accès est inconnu. Vérifiez la saisie. » |
| `access_code_used` (409) | « Ce code a déjà été utilisé. » |
| `access_code_expired` (409) | « Ce code a expiré. » |
| `access_code_not_for_account` (403) | « Ce code est réservé à un autre compte. » |
| `access_code_already_granted` (409) | « Un accès offert est déjà en cours sur votre compte. » + rechargement du droit. |
| Toute autre erreur | « Impossible d'activer ce code. » |

Aucun code d'erreur brut n'est affiché ; le champ **n'est pas vidé** en cas d'échec (l'utilisateur
corrige au lieu de retaper).

---

## Critères d'acceptation

- [ ] La saisie du code est **sur l'écran du plan**, jamais sur l'écran de connexion.
- [ ] Sans droit en cours : la section de saisie est visible ; avec droit en cours : elle est
      remplacée par le bandeau, et **aucun second champ** n'est proposé (pas de cumul à l'écran).
- [ ] Le bandeau affiche le **terme daté et heuré**, et la phrase « votre offre et votre quota de
      tokens n'ont pas changé ».
- [ ] Le bouton est désactivé pendant l'appel et sur champ vide.
- [ ] Chaque refus du backend a un message en français, actionnable, jamais un code brut.
- [ ] L'échec du chargement du droit **ne casse pas** l'écran de facturation.
- [ ] **Design system** : aucune couleur, police ou espacement hors jetons `--cg-*` ; boutons
      `mat-flat-button color="primary"` / `mat-stroked-button`, champ `mat-form-field` — rien
      d'inventé. La passe de cohérence F-56 et l'identité SF-49-03 ne sont pas touchées.
- [ ] Tests du composant : succès, chaque refus, section masquée quand un droit est en cours.

---

## Périmètre

### Hors scope (explicite)

- L'émission d'un code (SF-62-03).
- Toute saisie de code ailleurs que sur `/billing`.
- Toute modification des sections existantes (abonnements, option Forge, recharges, jauge) au-delà de
  l'ajout de la nouvelle section.

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format | Normalisation |
|---|---|---|---|---|
| `code` | Oui | 32 | non vide après trim | `trim()`, majuscules à l'envoi (le serveur renormalise) |

---

## Technique

### Endpoints consommés

| Méthode | URL |
|---|---|
| GET | `/api/access-code/grant` |
| POST | `/api/access-code/redeem` |

### Composants Angular

- `BillingComponent` — nouvelle section « code d'accès » + bandeau de droit en cours.
- `AccessCodeService` (core/services) — les deux appels.
- `access-code.models.ts` (core/models) — `AccessGrantView`, `RedeemAccessCodeRequest`.

### Migration Liquibase

- [ ] Non applicable.

---

## Plan de test

### Tests unitaires (composant)

- [ ] Activation réussie → le droit est affiché, la section de saisie disparaît, un message de succès
      est montré.
- [ ] Chaque code d'erreur backend → le message français correspondant.
- [ ] Champ vide → aucun appel réseau.
- [ ] Échec du `GET /grant` → l'écran reste rendu, la section de saisie reste proposée.

### Isolation utilisateur

- [x] Applicable — le droit affiché vient du serveur, filtré sur le `user_id` du JWT ; l'écran ne
      passe **aucun** identifiant d'utilisateur.

---

## Dépendances

- `SF-62-01` — **doit être mergée avant** (contrat d'API).

---

## Notes et décisions

- **D7 — Sous les abonnements, pas au-dessus.** Le chemin normal est de payer ; le code est
  l'alternative. Le mettre en tête ferait de l'exception la première chose lue.
- **D8 — L'écran ne calcule pas l'expiration.** Il affiche `active` tel que le serveur le renvoie :
  une horloge de navigateur décalée ne doit pas décider d'un droit.
