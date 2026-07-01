# Mini-spec — [F-03 / SF-03-01] Chiffrement KMS (socle crypto BYOK)

## Identifiant

`F-03 / SF-03-01`

## Feature parente

`F-03` — BYOK (Bring Your Own Key)

## Statut

`ready`

## Date de création

2026-07-01

## Branche Git

`feat/SF-03-01-chiffrement-kms`

---

## Objectif

> Fournir le socle cryptographique réutilisable qui chiffre/déchiffre une clé API utilisateur via
> **AWS KMS envelope encryption** (OQ-06 tranchée), sans jamais exposer ni journaliser la clé en clair.

---

## Comportement attendu

### Cas nominal

- `ByokKeyCipher.encrypt(plaintextApiKey)` :
  1. `KmsEnvelopeCipher` appelle KMS `GenerateDataKey` (AES_256) sur `app.byok.kms-key-id` → obtient
     une **data key en clair** + la **data key chiffrée** (CiphertextBlob).
  2. Chiffre localement la clé API en `AES/GCM/NoPadding` avec la data key en clair et un IV aléatoire
     (12 octets). Le tag GCM (128 bits) est inclus dans le ciphertext.
  3. Efface la data key en clair de la mémoire, puis retourne un `EncryptedKey`
     `{encryptedDataKey (b64), iv (b64), ciphertext (b64)}`.
- `ByokKeyCipher.decrypt(encryptedKey)` :
  1. KMS `Decrypt` de la data key chiffrée → data key en clair.
  2. Déchiffre le ciphertext en AES-GCM avec l'IV → clé API en clair (retournée à l'appelant, jamais stockée).
- Impl `LocalAesByokKeyCipher` (dev/tests) : AES-GCM avec une clé maître de test (`app.byok.local-key`,
  base64 32 octets), sans dépendance à un vrai KMS. Même contrat `EncryptedKey`.
- Sélection de l'implémentation (bean `ByokKeyCipher`) :
  - `app.byok.kms-key-id` non vide → `KmsEnvelopeCipher` (prod/staging).
  - sinon `app.byok.local-key` non vide → `LocalAesByokKeyCipher` (dev/tests).
  - sinon → `DisabledByokKeyCipher` (**impl dormante**).

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| BYOK non configuré (ni KMS ni local) : appel à encrypt/decrypt | `ByokDisabledException` levée | 503 (`byok_unavailable`) |
| Échec KMS (GenerateDataKey/Decrypt) | Exception technique loggée sans secret, remonte au handler | 500 générique |
| IV/ciphertext altéré (tag GCM invalide au decrypt) | Exception au déchiffrement (AEADBadTag) | 500 générique |

> Le mapping 503 de `ByokDisabledException` est ajouté au `GlobalExceptionHandler` (contrat prêt pour
> l'endpoint d'ajout de clé de SF-03-02). Aucun endpoint n'est créé dans cette SF.

---

## Critères d'acceptation

- [ ] Interface `ByokKeyCipher` avec `EncryptedKey encrypt(String)` et `String decrypt(EncryptedKey)`.
- [ ] `KmsEnvelopeCipher` implémente l'envelope encryption (GenerateDataKey + AES-GCM local + Decrypt).
- [ ] `LocalAesByokKeyCipher` : round-trip encrypt→decrypt rend la clé d'origine, sans KMS.
- [ ] Bean `ByokKeyCipher` sélectionné selon la config ; **dormant** (`DisabledByokKeyCipher`) si rien
      n'est configuré, sans empêcher le démarrage de l'application.
- [ ] `DisabledByokKeyCipher.encrypt/decrypt` lève `ByokDisabledException` → 503 `byok_unavailable`.
- [ ] La clé API en clair n'est **jamais** journalisée ni stockée dans `EncryptedKey` (seulement le blob chiffré).
- [ ] Aucune régression : l'application démarre avec le profil `test` (BYOK local) et le build est vert.

---

## Périmètre

### Hors scope (explicite)

- Table `user_api_keys`, entité, endpoints d'ajout/suppression/statut → **SF-03-02**.
- Bascule Hosted/BYOK et usage dans le chat → **SF-03-03**.
- Écran de réglages → **SF-03-04**.
- Rotation de clé KMS (gérée côté infra, automatique).

---

## Contraintes de validation

| Champ | Obligatoire | Format / Valeurs autorisées | Normalisation |
|-------|-------------|----------------------------|---------------|
| `app.byok.kms-key-id` | Non (dormant si absent) | alias/ARN/id KMS | — |
| `app.byok.region` | Non (défaut `eu-west-3`) | région AWS | — |
| `app.byok.local-key` | Non | base64 d'une clé AES 256 bits (32 octets) | décodée au démarrage |
| `EncryptedKey.*` | Oui | chaînes base64 | — |

---

## Technique

### Endpoint(s)

Aucun (socle interne).

### Tables impactées

Aucune.

### Migration Liquibase

- [ ] Non applicable (aucun changement de schéma dans cette SF).

### Composants

- `fr.claudegateway.byok.ByokKeyCipher` (interface)
- `fr.claudegateway.byok.EncryptedKey` (record — blob chiffré, jamais de clair)
- `fr.claudegateway.byok.KmsEnvelopeCipher` / `LocalAesByokKeyCipher` / `DisabledByokKeyCipher`
- `fr.claudegateway.byok.ByokProperties` / `ByokConfig`
- `fr.claudegateway.byok.ByokDisabledException` + mapping `GlobalExceptionHandler` (503)
- Dépendance Maven `software.amazon.awssdk:kms` (BOM déjà présent)

---

## Plan de test

### Tests unitaires

- [ ] `LocalAesByokKeyCipherTest` — round-trip : `decrypt(encrypt(k)) == k` ; deux chiffrements de la
      même clé produisent des ciphertext/IV différents (IV aléatoire).
- [ ] `KmsEnvelopeCipherTest` — avec `KmsClient` mocké : `GenerateDataKey` renvoie une data key connue ;
      encrypt produit un blob ; `Decrypt` renvoie la même data key ; round-trip rend la clé d'origine.
      Vérifie que l'appel KMS cible bien `kms-key-id`.
- [ ] `DisabledByokKeyCipherTest` — encrypt et decrypt lèvent `ByokDisabledException`.
- [ ] `EncryptedKey` ne contient jamais la clé en clair (le `toString`/champs ne portent que du base64 chiffré).

### Tests d'intégration

- [ ] Le contexte Spring démarre en profil `test` et expose un bean `ByokKeyCipher` (local) sans
      empêcher le démarrage.

### Isolation utilisateur

- [ ] Non applicable — socle crypto sans accès aux données (l'isolation `user_id` est portée par SF-03-02).

---

## Dépendances

### Subfeatures bloquantes

- Aucune.

### Questions ouvertes impactées

- [x] `OQ-06` — tranchée le 2026-07-01 (AWS KMS envelope encryption). Cette SF l'implémente.

---

## Notes et décisions

- **Provider-neutralité du blob** : `EncryptedKey` est un conteneur base64 (`encryptedDataKey`, `iv`,
  `ciphertext`) indépendant de l'implémentation ; c'est l'impl qui l'a produit qui sait le relire.
- **Zéro clair persisté/loggé** : la clé API ne transite qu'en argument/valeur de retour ; les data
  keys en clair sont effacées après usage ; aucune journalisation de secret.
- **Dormance** : conforme à la règle « ne pas empêcher le démarrage » — l'absence de configuration
  KMS n'échoue qu'au moment d'un usage réel (ajout de clé), traduit en 503 propre.
