# Mini-spec — F-248 / SF-248-09 Les accès

## Identifiant
`F-248 / SF-248-09`

## Feature parente
`F-248` — Les pancartes (cadrage §4.6 ; D8, D14 ; amendement F-247 SF-247-05)

## Statut
`done` (2026-11-05) · **Estimation :** 1,5 j (réestimé ¾ j : les extracteurs d'identité `ACCESS` sont livrés par SF-247-05) · **Branche :** `feat/SF-248-09-les-acces` · **Dépend de :** SF-248-07, SF-247-05 (mergées)

## Objectif
« Mes accès » : Jarvis montre les accès et moyens de connexion — boîtes reliées, droit OAuth Google Agenda, liens
d'agenda, jeton du runner, accès obtenus, identités relevées sur le poste — avec **compte, type, validité, état**
(et portée d'un droit), **jamais** la valeur, même partielle ou masquée.

## Comportement attendu

### Cas nominal
1. `JarvisAccessCards.accesses(userId, postes)` : boîtes (`OK` ⇒ valide, `AUTH_FAILED` ⇒ refusé, `PAUSED` ⇒ en
   pause), droits OAuth actifs (portée lisible `calendar.readonly`, jamais l'URL), liens ICS (`OK` / en panne), jeton
   du runner le plus récent non révoqué (échéance ⇒ « valide », « expire dans N j » ≤ 7 j, « expiré »), accès obtenus
   du poste ; 12 au plus ; une source en panne est omise.
2. Outil `etat` `famille = access` : ces accès rejoignent le **résultat lu** (une ligne « Accès … : type, état,
   valide jusqu'au … ») et ses pancartes **ciblées** — lu ⇒ montré ; les identités du poste (`ACCESS`, extracteurs
   SF-247-05 : `aws sts`, `az account`, `kubectl config`, `glab/gh auth status`) arrivent par le chemin de SF-248-07.
3. Gabarit `ACCES` : liste blanche `account`, `kind`, `expires_at`, `status`, `scope` ; une identité du registre y est
   ramenée (`identity_type` ⇒ type, `validity` ⇒ validité) ; tons : « refusé / expiré / en panne » ⇒ alerte, « expire
   dans » ⇒ attend.
4. Plein écran `fait:acces:<source>.<id>` (`JarvisAccessCardResolver`) : liste blanche seule ; une identité du
   registre (`fait:etat:…`) ne rend au plein écran que compte, type, validité, état.

### Cas d'erreur
| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Accès d'un autre compte | introuvable | 404 |
| Source en panne | omise, les autres restent | — |
| Attribut hors liste (valeur, jeton, chiffré, 4 derniers caractères, empreinte, URL) | jamais émis | — |

## Contraintes de validation
≤ 12 accès ; « expire dans » à 7 jours ; portée sans `://`.

## Critères d'acceptation
- [x] Les six sources rendent compte / type / validité / état, tons justes.
- [x] **Corpus « aucun secret » (100 %)** : 15 secrets semés (chiffrés, clés, IV, 4 derniers caractères, empreintes d'URL et de jeton, jetons GitLab / GitHub / AWS, mot de passe dans une note, valeur d'un fait `ACCESS`) — aucun dans les fiches, les lignes ajoutées au résultat ni aucun plein écran.
- [x] Bob ne voit aucun accès d'Alice (404 sur chaque plein écran).

## Plan de test
- Intégration : `JarvisAccessCardsIntegrationTest` (3 : lecture « mes accès », plein écran + isolation, validité).
- Non-régression : `StateExtractorsLot2Test` (corpus d'extraction F-247), `JarvisCardTemplatesTest`, suites complètes.

## Impact
- Backend : `jarvis/cards/JarvisAccessCards`, `JarvisAccessCardResolver` ; `JarvisStateTool` (setter facultatif, famille `access`) ; `JarvisCardTemplates` (tons « panne », « pause »).
- Aucune table, aucun endpoint nouveau (famille `acces` du plein écran existant).
- Préoccupations transversales : **Contexte tenant** — `JarvisMailAccountRepository.findByUserId…`, `JarvisCalendarGrantRepository.findByUserId…`, `JarvisCalendarFeedRepository.findByUserId…`, `RunnerTokenRepository.findByUserIdAndHostId…`, `AccesObtenuRepository.findByUserIdAndHostId…`, `RunnerHostService.list(userId)`.

## Arbitrages (réversibles)
- Les accès reliés entrent par l'outil `etat` (`famille = access`), en **s'ajoutant au résultat lu** : une pancarte ne montre jamais ce que Jarvis n'a pas lu ; aucun outil ni consigne ajoutés.
- Le jeton du runner est lu par poste du client visé (le plus récent non révoqué).

## Risque résiduel signalé
Le registre F-247 n'applique pas de filtre de secrets à l'enregistrement (il compte sur `SecretFilter` des
extracteurs) ; une observation hors extracteurs portant un secret dans l'attribut `value` serait dite par le bloc
« État connu ». Les pancartes, elles, ne l'émettent pas (liste blanche, test).

## Hors périmètre
Le banc et la planche (SF-10).
