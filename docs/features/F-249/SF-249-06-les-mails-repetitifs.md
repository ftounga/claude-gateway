# Mini-spec — F-249 / SF-249-06 Les mails répétitifs : proposer, ignorer par règle

## Identifiant
`F-249 / SF-249-06`

## Feature parente
`F-249` — Les mails et l'agenda Outlook de vos clients (cadrage D13)

## Statut
`in-progress`

## Date de création
2026-11-07

## Branche Git
`feat/SF-249-06-mails-repetitifs`

## Objectif
Après les premiers dépôts d'un client, l'application repère les expéditeurs et objets qui reviennent (alertes,
notifications automatiques) et propose de les ignorer ; une règle acceptée écarte ces mails **avant toute IA**,
réversible d'un clic.

## Comportement attendu

### Cas nominal
1. **Repérage** (dans le travail `MAIL_DEPOSIT`, sans IA), quand le client a au moins **10** courriers déposés en cache :
   - **expéditeur** qui revient **≥ 3 fois** avec l'indice `bulk` ou une adresse automatique (`noreply`, `alert`,
     `notification`, `monitoring`, `jira`, `gitlab`…) ;
   - **objet** répété **≥ 3 fois** une fois normalisé (préfixes `RE:/TR:/FW:` retirés, chiffres neutralisés en `#`,
     casse et accents ignorés).
   Chaque candidat devient une **proposition** (`jarvis_mail_ignore_offers`, `PENDING`) avec son nombre d'occurrences,
   jamais reproposée une fois acceptée ou refusée. Une annonce `IGNORE_OFFER` « 3 mails répétitifs à trier chez
   EDENRED » (lien `/jarvis`) invite à répondre ; pas de push.
2. **Accepter** (`POST /api/jarvis/client-mail/offers/{id}/accept`) : crée la règle (`jarvis_mail_ignore_rules`,
   client, `SENDER` adresse ou `SUBJECT` objet normalisé) et classe **tout de suite** « à ignorer » (raison
   « Ignoré par votre règle ») les courriers du client qui y répondent et ne sont pas encore triés ou triés « à lire ».
   **Refuser** (`…/decline`) : la proposition est close.
3. **Avant toute IA** : dans le travail `MAIL_DEPOSIT`, un courrier déposé qui répond à une règle de son client est
   classé « à ignorer » (raison « Ignoré par votre règle ») **avant** le tri F-242 : aucun appel au cerveau rapide, ni
   rattachement, ni annonce.
4. **Réversible** : `DELETE /api/jarvis/client-mail/rules/{id}` retire la règle ; les courriers qu'elle avait écartés
   (raison « Ignoré par votre règle ») depuis 7 jours repassent `EN_ATTENTE` et un travail les retrie.
5. **Lecture** : `GET /api/jarvis/client-mail` gagne `offers` (en attente) et `rules` (actives), par client.

### Cas d'erreur
| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Proposition ou règle d'un autre compte | introuvable | 404 |
| Proposition déjà traitée | refus | 409 |
| Moins de 10 courriers déposés | aucune proposition | — |

## Contraintes de validation
| Champ | Règle |
|---|---|
| valeur de règle | ≤ 200 caractères |
| propositions ouvertes par client | 10 au plus |

## Critères d'acceptation
- [ ] Après 12 dépôts dont 4 de `alertes@monitoring…` (bulk), une proposition « expéditeur » apparaît, une seule fois.
- [ ] Un objet « [PROD] Job 1234 KO » répété avec des numéros différents donne une proposition « objet ».
- [ ] Accepter ⇒ les courriers concernés passent « à ignorer » ; le suivant est écarté **sans appel au modèle**.
- [ ] Retirer la règle ⇒ ces courriers repassent au tri.
- [ ] **Isolation** : B ne voit ni n'accepte les propositions de A (404) ; une règle d'EDENRED ne touche pas CAGIP.

## Plan de test
- **Unitaires** `ClientMailIgnoreRulesTest` : normalisation d'objet, adresse automatique, correspondance.
- **Intégration** `ClientMailIgnoreIntegrationTest` : repérage, accepter/refuser, avant toute IA (fournisseur
  vérifié jamais appelé), retrait réversible, **isolation A/B** et par client.
- Suite complète backend à la review.

## Impact
- **Tables** : `jarvis_mail_ignore_rules`, `jarvis_mail_ignore_offers` (migration `249-06-jarvis-mail-ignore.xml`,
  réversible).
- **Endpoints** : `POST /api/jarvis/client-mail/offers/{id}/accept|decline`, `DELETE /api/jarvis/client-mail/rules/{id}`,
  `GET /api/jarvis/client-mail` (+ `offers`, `rules`).
- **Backend** : `ClientMailIgnore*`, `ClientMailDepositJobHandler`, `ClientMailController`, `ClientMailSettingsService`.
- **Consignes** : aucune ne change.

## Préoccupations transversales
- **Contexte tenant** : règles et propositions par (`user_id`, `host_id`) ; routes sous le principal.

## Hors périmètre
L'écran (SF-249-07).
