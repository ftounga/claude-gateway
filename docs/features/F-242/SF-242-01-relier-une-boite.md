# Mini-spec — F-242 / SF-242-01 Relier une boîte : secret chiffré, test, révocation

## Identifiant
`F-242 / SF-242-01`

## Feature parente
`F-242` — Jarvis et vos boîtes mail (cadrage `CADRAGE-F-242-jarvis-et-vos-boites-mail.md`, D1, D2, D3, D18)

## Statut
`done` (2026-11-04) · **Estimation :** 0,75 j · **Branche :** `feat/SF-242-01-relier-une-boite` · **Dépend de :** —

## Objectif
Le PO relie ses boîtes Google (deux au plus) avec un mot de passe d'application, stocké chiffré, testable et révocable.

## Comportement attendu

### Cas nominal
1. **Relier** — `POST /api/jarvis/mail/accounts` `{address, label, password}` : le mot de passe (espaces retirés, Google
   l'affiche par groupes de 4) est **d'abord testé** en IMAP (connexion + `LIST` des dossiers, découverte SPECIAL-USE)
   **puis** en SMTP (authentification seule, aucun envoi). Les deux réussissent ⇒ le secret est chiffré par
   `ByokKeyCipher` (colonnes `encrypted_data_key`, `cipher_iv`, `ciphertext`, patron `user_git_credentials`) et la boîte
   est enregistrée `OK` (`last_ok_at` = maintenant). Une adresse **déjà reliée** par ce compte voit son secret remplacé
   (relier à nouveau = réparer un `AUTH_FAILED`).
2. **Lister** — `GET /api/jarvis/mail/accounts` : `[{id, address, label, provider, status, secretLast4, lastOkAt,
   lastError, createdAt}]`. Le secret n'est **jamais** renvoyé.
3. **Tester** — `POST /api/jarvis/mail/accounts/{id}/test` : déchiffre, refait IMAP + SMTP, met à jour `status`,
   `last_ok_at`/`last_error`, rend la vue et les dossiers trouvés (`folders: {drafts, trash, all, sent}`).
4. **Révoquer** — `DELETE /api/jarvis/mail/accounts/{id}` : supprime la ligne (et, dès SF-242-02, ses courriers en
   cache) ; l'écran invite à révoquer aussi le mot de passe chez Google (`myaccount.google.com/apppasswords`).
5. **Préréglage Google (D2)** : `imap.gmail.com:993` SSL et `smtp.gmail.com:465` SSL, pour gmail.com et Workspace.
   Aucun champ serveur libre ; les hôtes sont des réglages (`app.jarvis.mail.*`) pour les tests seulement.
6. **Dossiers (D3)** : trouvés par les attributs SPECIAL-USE (`\Drafts`, `\Trash`, `\All`, `\Sent`), noms localisés
   jamais écrits en dur ; repli sur les noms anglais usuels **seulement** si le serveur n'annonce aucun attribut
   (serveur de test).
7. **Écran** — bouton « Mes boîtes » dans les réglages de `/jarvis` (à côté de « Mon portrait »), volet : pas-à-pas en
   3 lignes (validation en deux étapes ; créer le mot de passe sur `myaccount.google.com/apppasswords` ; le coller),
   formulaire adresse / libellé / mot de passe (champ masqué), liste des boîtes avec état, `…last4`, « Tester »,
   « Révoquer » (confirmation). **Masqué en présentation.**
8. **Purge du compte** : `AccountService` supprime les boîtes de l'utilisateur.

### Cas d'erreur
| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Mot de passe refusé par Google (IMAP ou SMTP) | « Google refuse ce mot de passe d'application… » ; **rien n'est écrit** | 400 `mail_auth_failed` |
| Serveur injoignable / délai | « Google ne répond pas, réessayez » ; rien n'est écrit | 503 `mail_unavailable` |
| Adresse invalide, libellé > 40, mot de passe vide ou > 64 | refusé avant tout appel réseau | 400 `invalid_mail_account` |
| 3e boîte (adresse nouvelle alors que 2 sont reliées) | refusé avant tout appel réseau | 400 `mail_accounts_full` |
| Boîte d'un autre utilisateur (tester, révoquer) | introuvable | 404 `not_found` |
| Chiffrement non configuré | jamais de repli en clair | 503 (existant `ByokDisabledException`) |
| Test d'une boîte dont le secret est refusé | `status = AUTH_FAILED`, `last_error` renseigné, vue rendue | 200 |

## Contraintes de validation
| Champ | Règle |
|---|---|
| `address` | e-mail `local@domaine.tld`, ≤ 254, minuscules, unique par (`user_id`, `address`) |
| `label` | facultatif, ≤ 40 (défaut : « Perso » si `@gmail.com`, sinon « Pro ») |
| `password` | espaces retirés, 1..64 caractères |
| boîtes par compte | 2 au plus (D18) |
| `status` | `OK`, `AUTH_FAILED`, `PAUSED` |

## Critères d'acceptation
- [x] Avec un bon mot de passe, le test IMAP et SMTP réussit, puis la boîte est enregistrée `OK`.
- [x] Avec un mauvais mot de passe, le test échoue avec un message clair, et rien n'est enregistré.
- [x] Le secret est chiffré en base (aucun texte clair) et n'est jamais renvoyé : l'API ne montre que `secretLast4`.
- [x] « Révoquer » efface la boîte.
- [x] L'utilisateur B reçoit 404 sur les boîtes de A (tester, révoquer) et ne les voit pas dans sa liste.
- [x] La suppression du compte purge la table.
- [x] Une 3e boîte est refusée (400).
- [x] Le volet est absent en présentation.

## Plan de test
- **Unitaires** `MailFoldersTest` : découverte SPECIAL-USE avec noms localisés simulés (« [Gmail]/Corbeille »), repli
  sans attribut ; `JarvisMailPropertiesTest` : préréglage Google par défaut.
- **Intégration** `JarvisMailAccountApiIntegrationTest` avec **GreenMail** (IMAP + SMTP de test, dépendance de test) :
  relier OK, mauvais mot de passe ⇒ 400 et aucune ligne, chiffrement (aucun clair en base, `ByokKeyCipher` relit),
  relier deux fois la même adresse ⇒ une ligne, 3e boîte ⇒ 400, tester, révoquer, **isolation A/B** (404 croisés, liste),
  purge du compte, 401 sans jeton.
- **Frontend** `jarvis-mailboxes.component.spec.ts` : liste, relier, erreur affichée, révoquer après confirmation,
  le champ mot de passe est masqué ; `jarvis.component.spec.ts` : bouton absent en présentation.
- Liquibase (contexte de test démarre), suites complètes backend + frontend + build.

## Impact
- **Table** nouvelle `jarvis_mail_accounts` (migration `242-jarvis-mail-accounts.xml`, réversible). Les colonnes de
  relève (`inbox_uidvalidity`, `inbox_last_uid`, `next_poll_at`, `lease_until`) sont créées dès maintenant (§5 du
  cadrage) pour une seule migration de table.
- **Endpoints** nouveaux : `GET/POST /api/jarvis/mail/accounts`, `POST /api/jarvis/mail/accounts/{id}/test`,
  `DELETE /api/jarvis/mail/accounts/{id}`.
- **Backend** : paquet `fr.claudegateway.jarvis.mail` (nouveau), `AccountService` (purge), `JarvisConfig`
  (propriétés), `application.yml` (`app.jarvis.mail.*`), `pom.xml` (GreenMail, **test seulement**).
- **Frontend** : `jarvis/jarvis-mail.ts` (client), `jarvis/jarvis-mailboxes.component.ts` (volet), `jarvis.component.{ts,html}`.

**Préoccupation transversale « Contexte tenant »** — composants qui résolvent l'appartenance d'une boîte :
`JarvisMailAccountRepository.findByIdAndUserId` (seul accès par id), `JarvisMailAccountService` (tous les gestes),
`AccountService` (purge). Aucune autre préoccupation (pas d'auth, plan ni route ajoutés : le volet vit dans `/jarvis`).

## Arbitrages (documentés dans la PR)
- **GreenMail** en dépendance de **test** (variante de pile signalée) : un vrai serveur IMAP/SMTP local, sans réseau.
- **Relier à nouveau la même adresse remplace le secret** (pas de 409) : c'est le geste de réparation d'un `AUTH_FAILED`.
- **Repli de noms anglais** pour les dossiers si le serveur n'annonce aucun SPECIAL-USE (Gmail les annonce toujours).
- **Hôtes/ports en réglages** (`app.jarvis.mail.imap-*`, `smtp-*`, `ssl`) : préréglage Google par défaut, surchargé
  seulement par les tests ; pas de champ serveur dans l'API.

## Hors périmètre
La relève et le cache (SF-242-02) ; le tri (SF-242-03) ; tout geste sur le courrier (SF-242-05/06).
