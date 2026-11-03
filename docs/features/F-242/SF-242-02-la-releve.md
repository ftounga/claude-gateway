# Mini-spec — F-242 / SF-242-02 La relève : rien de neuf, rien d'appelé

## Identifiant
`F-242 / SF-242-02`

## Feature parente
`F-242` — Jarvis et vos boîtes mail (cadrage, D4, D5)

## Statut
`done` (2026-11-04) · **Estimation :** 1 j · **Branche :** `feat/SF-242-02-la-releve` · **Dépend de :** SF-242-01 (mergée, #1565)

## Objectif
Les nouveaux mails arrivent dans un cache chiffré toutes les 5 min, sans aucun téléchargement ni IA quand rien n'a changé.

## Comportement attendu

### Cas nominal
1. **Worker serveur** `JarvisMailPollWorker` (toutes les 60 s, `@ConditionalOnProperty app.jarvis.mail.enabled`) appelle
   `JarvisMailPoller.runOnce()` : les boîtes dues (`next_poll_at ≤ maintenant`, statut `OK` ou `AUTH_FAILED` en
   retard échu) sont **réservées** une à une par `UPDATE` conditionnel sur `lease_until` (patron `claimSlot`, bail
   2 min) : deux pods ne relèvent jamais la même boîte en même temps.
2. **`STATUS INBOX (UIDNEXT UIDVALIDITY)`** (dossier fermé) : si `UIDVALIDITY` est inchangée et `UIDNEXT - 1 ≤
   inbox_last_uid` ⇒ **rien de neuf** : aucun `SELECT`, aucun `FETCH`, aucun appel au modèle ; compteur
   `mail_polls_empty`.
3. **Du neuf** : `SELECT` en lecture seule (`READ_ONLY`, aucun drapeau modifié), UID `> inbox_last_uid` (100 au plus par
   passage, les plus récents d'abord si plus), en-têtes (`ENVELOPE`, drapeaux, `List-Unsubscribe`, `Precedence`,
   `Auto-Submitted`, `In-Reply-To`, `References`), **extrait texte de 2 000 caractères au plus** (texte brut, sinon HTML
   réduit au texte), présence d'un `text/calendar` / `.ics` (`has_calendar`, pour F-243). **Le corps complet n'est
   jamais stocké.**
4. **Premier passage** (`inbox_last_uid` nul) ou **`UIDVALIDITY` changée** ⇒ resynchronisation sur **7 jours**
   (`SEARCH SINCE`, 100 au plus), dédoublonnée par `message_id` : aucun doublon.
5. **Cache** `jarvis_mail_items` : en-têtes + extrait **scellés** (`ByokKeyCipher`, une colonne `sealed`), `uid`,
   `uidvalidity`, `message_id`, `thread_ref`, `received_at`, `has_calendar`, `flags`, `bulk` (indice de règle pour le
   tri SF-242-03), `triage = EN_ATTENTE`. Unicité (`account_id`, `uidvalidity`, `uid`). Écrit **sous le `user_id` de
   la boîte**.
6. **Mesure** (aucun contenu) dans `jarvis_usage_days` : `mail_polls`, `mail_polls_empty`, `mail_fetched`
   (+ `mail_triage_corrected`, posé pour SF-242-03).
7. **Purge à 30 jours** (`received_at`, quotidienne) ; « Révoquer » et la purge du compte effacent les courriers
   (`JarvisMailPurge`).
8. **Lecture du cache** `GET /api/jarvis/mail/items?triage=&limit=` (20 par défaut, 100 au plus) : courriers du compte,
   les plus récents d'abord, en-têtes descellés (`from`, `fromName`, `subject`, `snippet`, `receivedAt`, `triage`,
   `account`, `hasCalendar`).

### Cas d'erreur
| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Mot de passe révoqué chez Google | `status = AUTH_FAILED`, retard exponentiel (5 min × 2ⁿ, ≤ 6 h), **une** notification `MAIL_AUTH_FAILED` (au passage OK → AUTH_FAILED seulement) | — |
| Google injoignable | `last_error`, statut inchangé, relève suivante dans 5 min, aucune notification | — |
| Bail tenu par un autre pod | la boîte est sautée | — |
| Boîte révoquée pendant la relève | l'écriture ne trouve plus la boîte ; rien n'est gardé | — |
| `triage` inconnu dans `GET /items` | refusé | 400 |
| Coupe-circuit `APP_JARVIS_MAIL_ENABLED=false` | aucune connexion périodique | — |

## Contraintes de validation
| Champ | Règle |
|---|---|
| extrait | ≤ 2 000 caractères |
| nouveaux mails par passage | ≤ 100 |
| resynchronisation | 7 jours |
| rétention du cache | 30 jours |
| `triage` | `A_TRAITER`, `A_LIRE`, `A_IGNORER`, `EN_ATTENTE` |

## Critères d'acceptation
- [x] Sans nouveau mail, aucun `FETCH` et aucun appel au modèle (le test le prouve).
- [x] Trois nouveaux mails donnent trois lignes, sans doublon à la relève suivante.
- [x] Si `UIDVALIDITY` change, la resynchronisation se fait sans doublon (par `message_id`).
- [x] Deux pods ne relèvent jamais la même boîte en même temps.
- [x] Un mot de passe révoqué chez Google donne `AUTH_FAILED` et une seule notification.
- [x] Le corps complet n'est jamais stocké.

## Plan de test
- **Unitaires** `MailSnippetTest` (extrait borné, HTML → texte, `text/calendar` repéré), `JarvisMailPollerBackoffTest`
  (retard exponentiel borné), indices `bulk`.
- **Intégration** `JarvisMailPollIntegrationTest` (GreenMail) : relève vide sans `FETCH` (serveur espionné : compte des
  commandes) et sans appel au fournisseur, 3 mails ⇒ 3 lignes puis 0 doublon, `UIDVALIDITY` changée ⇒ pas de doublon,
  bail concurrent (deux réservations, une seule gagne), `AUTH_FAILED` + une notification, corps long ⇒ extrait 2 000,
  **isolation** (lignes sous le `user_id` de la boîte ; `GET /items` de B vide), purge 30 j, révocation efface.
- Suites complètes backend + frontend + build.

## Impact
- **Tables** : nouvelle `jarvis_mail_items` (`242-jarvis-mail-items.xml`) ; `jarvis_usage_days` + 4 compteurs
  (`242-jarvis-usage-mail.xml`). Réversibles.
- **Endpoint** nouveau : `GET /api/jarvis/mail/items`.
- **Backend** : `jarvis/mail/*` (poller, worker, snippet, item, repository, purge), `JarvisUsageDay`/`JarvisUsageService`
  (compteurs), `PushEvent` (`MAIL_AUTH_FAILED`), `JarvisMailAccountRepository` (réservation).

**Préoccupation transversale « Contexte tenant »** — le worker résout le tenant **par la boîte** : chaque ligne est écrite
sous `account.userId` ; `JarvisMailItemRepository` filtre par `user_id` sur toute lecture ; `GET /items` sur le principal.

## Arbitrages (documentés dans la PR)
- **Un seul scellé par courrier** (`sealed` = JSON des en-têtes + extrait, patron `PortraitSeal`) plutôt que trois
  colonnes chiffrées : un chiffrement KMS par courrier au lieu de trois.
- **Premier passage = 7 jours** (comme une resynchronisation), pour que le PO ait du contenu dès la première relève.
- **Compteurs dans `jarvis_usage_days`** (pas de Micrometer dans l'application).
- **Notification `MAIL_AUTH_FAILED`** ajoutée au catalogue F-185, corps neutre, lien `/jarvis`.

## Hors périmètre
Le tri (SF-242-03) ; le bref (SF-242-04) ; la lecture du corps complet (SF-242-05).
