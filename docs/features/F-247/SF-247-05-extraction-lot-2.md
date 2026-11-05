# Mini-spec — F-247 / SF-247-05 Extraction, lot 2 (+ amendement F-248 : identités ACCESS)

## Identifiant
`F-247 / SF-247-05`

## Feature parente
`F-247` — Le registre d'état (cadrage §4.2) ; amendement porté par F-248 D14 (famille `ACCESS`) et D15 (attribut `url`)

## Statut
`done` (2026-11-05) · **Estimation :** 2 j (réestimé 1 j) · **Branche :** `feat/SF-247-05-extraction-lot-2` · **Dépend de :** SF-247-04

## Objectif
Étendre l'extraction déterministe à tout ce qui se lit couramment sur le poste — déploiements, cloud, infra, base, git,
API génériques — et aux **identités du poste en lecture seule** (compte, type, validité, état), sans jamais garder un secret.

## Comportement attendu

### Cas nominal
1. **Déploiements** : `kubectl`/`oc get deploy|sts -o json` (image, réplicas prêts, version d'étiquette) ; tableau
   strict `NAME READY UP-TO-DATE AVAILABLE AGE [CONTAINERS IMAGES SELECTOR]` (confiance `MOYENNE`) ;
   `rollout status` (« successfully rolled out ») ; `helm list|status|history -o json` (statut, révision, chart,
   version) ; `argocd app get|list -o json` (synchro, santé, révision, images). Contexte et espace de noms tirés des
   drapeaux (`--context`, `-n`) ou de la sortie.
2. **Cloud** : `aws ecs describe-services`, `lambda get-function`, `cloudformation describe-stacks`,
   `rds describe-db-instances` (JSON, clé = ARN de la sortie) ; `az … show|list` (toute ressource dont l'`id` est un
   chemin `/subscriptions/…` : état, approvisionnement, version).
3. **Infra** : `terraform output -json` (sorties **non sensibles** seulement, 30 au plus) ; `terraform state list`
   (nombre de ressources, toutes lignes conformes). Jamais `plan` ni `apply`.
4. **Base** : version de schéma lue par `psql` sur `databasechangelog` / `flyway_schema_history` avec
   `order by … desc limit 1` (sortie alignée stricte).
5. **Git** : `git status` (branche, avance/retard, propre/modifié), `git branch -vv` (branche courante, suivi, dernier
   commit), `git log -n|--oneline` **sans référence ni filtre** (HEAD), `git describe` (étiquette). Dépôt de
   `git -C <chemin>`, sinon le **nom du sujet** (`{subject}` remplacé par l'exécutant).
6. **API génériques** (repli, seulement si aucun extracteur spécialisé n'a couvert la sortie) : `curl` /
   `Invoke-RestMethod` en lecture, réponse JSON objet avec `status`/`state`/`version` au premier niveau → famille
   `OTHER`, `MOYENNE`, clé `API:<hôte><chemin>`.
7. **Identités `ACCESS`** (F-248 D14) : `aws sts get-caller-identity` (compte = ARN, type, validité),
   `az account show` (compte, type, état, nom de l'abonnement), `kubectl config current-context`,
   `glab auth status` / `gh auth status` (« Logged in to … as|account … », état). **Aucune ligne de jeton lue.**
8. **Filtre « aucun secret »** (`SecretFilter`) sur **toute** valeur, tous extracteurs : jetons GitLab/GitHub/Slack/
   Anthropic, clés AWS, JWT, clés privées, `Bearer`/`Basic`, `password=…`, identifiants dans une URL ⇒ fait rejeté.

### Cas d'erreur
| Situation | Comportement attendu |
|---|---|
| Tableau sans l'en-tête exact, `-A` (colonne NAMESPACE) | aucun fait |
| Sortie Terraform `sensitive: true` | jamais lue |
| `git log` avec une référence ou un filtre | aucun fait (le premier commit ne serait pas celui de HEAD) |
| Valeur ressemblant à un secret | rejetée |
| Commande d'écriture | jamais une lecture d'état (inchangé) |

## Contraintes de validation
Mêmes bornes que le lot 1 ; 30 sorties Terraform au plus ; 20 identités par sortie au plus.

## Critères d'acceptation
- [x] Chaque famille du lot 2 donne ses faits sur une sortie typique ; formes inconnues ⇒ rien.
- [x] Identités : compte, type, validité, état seulement ; aucune valeur de jeton.
- [x] Corpus « aucun secret » : 8 formes de secret, 0 sortie (100 %).
- [x] `{subject}` remplacé par le nom du sujet à l'écriture.

## Plan de test
- Unitaires : `StateExtractorsLot2Test` (6 : kube, helm/argo, cloud/infra, git/base, API/identités, corpus « aucun secret »).
- Intégration : `StateExtractJobIntegrationTest` (+1 : `git status` d'un tour → `GIT:<sujet>@main`).
- Non-régression : `StateExtractorsLot1Test`.

## Impact
- Backend : `jarvis/state/extract/DeployExtractors`, `CloudExtractors`, `RepoExtractors`, `AccessExtractors`, `GenericApiExtractor`, `SecretFilter` (nouveaux) ; `StateExtractors` (lot 2, repli, filtre) ; `StateExtractJobHandler` (`{subject}`).
- Aucune table, aucun endpoint, aucune migration.
- Préoccupations transversales : Contexte tenant — inchangé (exécutant de SF-247-04, lecture sous `user_id`).

## Hors périmètre
Les formes texte non prouvées par un échantillon réel (`glab ci status`, `flyway info`, `liquibase history` en texte) : elles restent listées par la couverture et vont aux constats (SF-247-14).
