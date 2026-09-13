# Mini-spec — F-110 / SF-110-01 — L'adresse de réception d'un client

> Base : `docs/features/F-110/CADRAGE-F-110-le-courriel-du-client.md` §2, §3, §7, §9 (cadrage validé par le PO,
> non rediscuté ici).

## Identifiant

`F-110 / SF-110-01`

## Feature parente

`F-110` — Le courriel du client : l'application m'envoie des courriels

## Statut

`done` — livrée le 2026-09-13 (PR #562)

## Date de création

2026-09-13

## Branche Git

`feat/SF-110-01-adresse-reception`

---

## Objectif

Pour chaque client (poste), l'utilisateur déclare dans l'en-tête du client (Forge et Vigie) une **adresse de
réception**, la **vérifie par un code à 6 chiffres** reçu à cette adresse, et la gateway sait résoudre, pour ce
poste, le destinataire des courriels : l'adresse vérifiée, ou à défaut — et en le disant — l'adresse du compte.

---

## Comportement attendu

### Cas nominal

1. **Lire** — `GET /api/runner-hosts/{hostId}/mail-address` rend l'état : adresse déclarée (ou nulle), vérifiée
   ou non (date), code en attente (envoyé à, expire à), adresse du compte, **destinataire effectif** et
   `fallback` (vrai quand le destinataire est l'adresse du compte).
2. **Déclarer** — `PUT …/mail-address` `{ "address": "franck.tounga@cagip.fr" }` :
   - l'adresse est normalisée (espaces retirés, minuscules) et validée ;
   - identique à l'adresse **déjà vérifiée** : rien ne change, aucun code n'est envoyé (idempotent) ;
   - sinon : l'adresse est enregistrée **non vérifiée** (une adresse précédemment vérifiée cesse aussitôt de
     recevoir — « changer l'adresse relance la vérification ») ; un code à 6 chiffres est tiré (aléatoire
     cryptographique), **seule son empreinte SHA-256 est conservée**, valable **15 minutes**, et envoyé à
     l'adresse saisie par le relais SMTP existant (délais bornés F-77) avec le nom du client dans l'objet.
3. **Vérifier** — `POST …/mail-address/verify` `{ "code": "123456" }` : code juste et non expiré → l'adresse est
   **vérifiée** (`verifiedAt`), le code est effacé ; le destinataire effectif devient cette adresse.
4. **Renvoyer** — `POST …/mail-address/code` : tire un nouveau code (l'ancien ne vaut plus), au plus un envoi
   par minute.
5. **Retirer** — `DELETE …/mail-address` : la ligne est effacée ; le poste revient au repli sur l'adresse du
   compte.
6. **Résolution (service, pour SF-110-02 et SF-110-04)** — `HostMailAddressService.resolveRecipient(userId,
   hostId)` rend `{ address, verifiedForClient, clientName }` : l'adresse vérifiée du poste, sinon l'adresse du
   compte avec `verifiedForClient=false`. **Jamais** une adresse non vérifiée du poste.
7. **L'écran** — dans l'en-tête du client, Forge (postes non hébergés) **et** Vigie, une ligne
   `app-host-mail-address` : « Courriels : franck.tounga@cagip.fr » (vérifiée), « Courriels : code envoyé à … —
   à confirmer » (en attente ; le repli est dit), ou « Courriels : aucune adresse vérifiée — envoi à l'adresse du
   compte ntounga@gmail.com » ; un bouton *Régler* ouvre un dialogue : saisie de l'adresse → *Envoyer le code* →
   saisie du code → *Vérifier* ; *Renvoyer le code* ; *Retirer l'adresse* ; rappel « vérifiez vos courriers
   indésirables la première fois ». Snackbar à la vérification. Silencieuse si l'état est illisible.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Adresse absente, > 254 caractères, format invalide | message explicite, rien n'est enregistré | 400 `mail_address_invalid` |
| Code faux | « Code incorrect » + essais restants ; au 5ᵉ échec le code est invalidé | 400 `mail_code_invalid` |
| Code expiré (> 15 min) ou invalidé après 5 échecs | « Code expiré : demandez-en un nouveau » | 400 `mail_code_expired` |
| Vérifier / renvoyer sans code en attente (rien déclaré, déjà vérifiée) | message explicite | 409 `mail_code_none` |
| Nouveau code demandé moins d'une minute après le précédent | « Patientez avant de redemander un code » | 429 `mail_code_throttled` |
| Relais SMTP en échec à l'envoi du code | « Le code n'a pas pu être envoyé » ; l'adresse reste en attente, renvoi possible | 502 `mail_code_not_sent` |
| Poste inconnu ou d'autrui | indiscernables | 404 `not_found` |
| Ni Forge ni Vigie (hors administrateur) | refus | 403 `atelier_forbidden` |
| Non authentifié | refus | 401 |

---

## Critères d'acceptation

- [ ] `PUT` d'une adresse valide l'enregistre non vérifiée et envoie un code à 6 chiffres à cette adresse ; la base ne contient que l'empreinte du code.
- [ ] `POST …/verify` avec le bon code rend `verified=true`, `recipient` = l'adresse, `fallback=false`.
- [ ] Un code faux est refusé (400), le 5ᵉ échec invalide le code ; un code expiré est refusé (400).
- [ ] Changer une adresse vérifiée la repasse non vérifiée : le destinataire redevient l'adresse du compte jusqu'à la nouvelle vérification.
- [ ] Redéclarer l'adresse déjà vérifiée n'envoie aucun code et ne change rien.
- [ ] Un deuxième code moins d'une minute après le premier est refusé (429).
- [ ] `resolveRecipient` ne rend jamais une adresse non vérifiée du poste ; sans adresse vérifiée, il rend l'adresse du compte avec `verifiedForClient=false`.
- [ ] `DELETE` retire l'adresse ; le repli est rendu.
- [ ] Isolation : l'adresse d'un poste d'autrui n'est ni lisible ni modifiable (404) ; l'accès exige Forge ou Vigie, administrateur compris.
- [ ] La ligne `app-host-mail-address` apparaît dans l'en-tête du client de la Forge (poste non hébergé) et de la Vigie, dit l'état et le repli ; le dialogue enchaîne adresse → code → vérifié.
- [ ] Supprimer le poste ou le compte efface son adresse de réception.
- [ ] DESIGN_SYSTEM : `mat-form-field` outline + `mat-error`, `MatDialog`, `MatSnackBar`, jetons `--cg-*`, aucune couleur nouvelle.

---

## Périmètre

### Hors scope (explicite)

- L'outil `email_me`, la file d'envoi, la limite quotidienne, le bloc du terminal (SF-110-02).
- Les pièces jointes (SF-110-03) et le résumé du matin par courriel (SF-110-04).
- Plusieurs adresses par poste ; un domaine d'envoi par client ; écrire à un tiers.
- La vérification SPF/DKIM/DMARC du domaine d'envoi (geste de déploiement).

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| `verified_at` | nul | posé à la vérification, remis à nul à tout changement d'adresse |
| `code_hash` | empreinte du code tiré | effacé à la vérification ou à l'invalidation |
| `code_expires_at` | maintenant + 15 min | — |
| `code_attempts` | 0 | +1 par code faux ; 5 → code invalidé |
| `code_sent_at` | maintenant | sert au délai d'une minute entre deux codes |

Comportements à la création : `user_id` = utilisateur du JWT, `host_id` = poste possédé (vérifié), une ligne au plus par poste.

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|-------------|----------------------------|---------|---------------|
| `address` | Oui | 254 | `local@domaine.tld`, sans espace ni caractère de contrôle, un seul `@`, domaine avec un point | une par poste | `trim()`, minuscules |
| `code` | Oui | 6 | exactement 6 chiffres | — | `trim()` |

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| GET | `/api/runner-hosts/{hostId}/mail-address` | JWT | droit Forge ou Vigie (ADMIN d'office) |
| PUT | `/api/runner-hosts/{hostId}/mail-address` | JWT | idem |
| POST | `/api/runner-hosts/{hostId}/mail-address/verify` | JWT | idem |
| POST | `/api/runner-hosts/{hostId}/mail-address/code` | JWT | idem |
| DELETE | `/api/runner-hosts/{hostId}/mail-address` | JWT | idem |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `host_mail_addresses` (nouvelle) | INSERT / SELECT / UPDATE / DELETE | une ligne par poste ; `user_id` + `host_id` ; FK `ON DELETE CASCADE` vers `runner_hosts` et `users` |
| `users` | SELECT | adresse du compte (repli) |
| `runner_hosts` | SELECT | possession du poste, nom du client |

### Migration Liquibase

- [x] Oui — `100-host-mail-addresses.xml` (premier numéro libre au-dessus du dernier sur `main`, revérifié après rebase).

### Composants

| Composant | Rôle |
|-----------|------|
| `mail/HostMailAddress` (+ repository) | entité, filtre `user_id` + `host_id` |
| `mail/HostMailAddressService` | déclarer, vérifier, renvoyer, retirer, **résoudre le destinataire** |
| `mail/MailAddresses` | normalisation / validation d'une adresse (pur) |
| `mail/HostMailAddressController` + `MailExceptionHandler` | endpoints, garde runner, erreurs |
| `email/EmailService` (+ `Smtp`, `Logging`) | `sendReceptionAddressCode(to, clientName, code)` |
| `shared/host-mail-address/*` (Angular) | ligne d'en-tête, dialogue, fonctions pures, service |
| `PostesComponent`, `VigieComponent` | la ligne dans l'en-tête du client |

---

## Plan de test

### Tests unitaires

- [ ] `MailAddressesTest` — normalisation, formats valides / invalides, longueur.
- [ ] `HostMailAddressServiceTest` — déclaration (empreinte seule, code envoyé), idempotence sur l'adresse vérifiée, changement → non vérifiée, code juste / faux / 5 échecs / expiré, délai d'une minute, échec SMTP, résolution (vérifiée, en attente → repli, absente → repli).
- [ ] `host-mail-address.spec.ts` — phrase d'en-tête (vérifiée / en attente / repli), validation adresse et code, messages d'erreur.
- [ ] `host-mail-address.component.spec.ts` — ligne : lecture, rendu, illisible → rien, *Régler* ouvre le dialogue. Dialogue : adresse → code, vérification, erreur dite, renvoi, retrait.

### Tests d'intégration

- [ ] `HostMailAddressApiIntegrationTest` — parcours complet PUT → verify (code capturé par un `EmailService` espion) → GET ; 400 adresse / code ; 409 sans code ; 429 ; 404 poste d'autrui ; 403 sans droit ; ADMIN autorisé ; DELETE ; suppression du poste efface la ligne.

### Isolation utilisateur

- [x] Applicable — un utilisateur B ne lit ni ne modifie l'adresse du poste de A (404) ; le service filtre toujours `user_id` + `host_id`.

---

## Dépendances

### Subfeatures bloquantes

- Aucune (F-48 postes, F-106 espaces, F-107 droits : livrés).

### Questions ouvertes impactées

- Aucune.

---

## Préoccupations transversales

- **Contexte tenant : oui** — nouvelle donnée par poste. Composants qui résolvent le poste : `RunnerHostService.requireOwned` (réutilisé tel quel), `HostMailAddressService` (filtre `user_id` + `host_id` sur chaque requête). Aucun composant existant ne change sa résolution.
- **Plans / limites : oui** — garde `AtelierAccessService.requireRunnerAccess()` (Forge ou Vigie, ADMIN via `SpaceEntitlementService` / `AdministratorEntitlement`) ; aucun nouveau gate, aucun quota lu.
- **Auth / Principal : non.** **Navigation / routing : non** (dialogue depuis l'en-tête existant).
- **Sécurité** : code à usage unique haché, 5 essais, 15 min, une minute entre deux envois ; le code n'est jamais journalisé en production (le fournisseur `logging` de dev le journalise sous `[EMAIL:DEV-STUB]`, comme les liens existants).

---

## Notes et décisions

- **FK en cascade** vers `runner_hosts` et `users` plutôt qu'une purge explicite : la suppression d'un poste
  (`deleteWithCredentials`) et celle d'un compte effacent l'adresse sans toucher à `AccountService`.
- **Changer d'adresse coupe l'ancienne immédiatement** : si l'utilisateur change, c'est que l'ancienne ne convient
  plus ; le repli sur l'adresse du compte (vérifiée par l'inscription ou Google) est plus sûr.
- **Envoi du code synchrone** avec délais SMTP bornés (5 s, F-77), comme la vérification d'adresse du compte : un
  seul courriel court, dont l'échec doit être dit tout de suite à l'écran.
