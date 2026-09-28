# Mini-spec — F-84 / SF-84-09 — La garde avant déploiement

## Identifiant

`F-84 / SF-84-09`

## Feature parente

`F-84` — Le tour survit à son flux

## Statut

`ready`

## Date de création

2026-09-28

## Branche Git

`feat/SF-84-09-garde-avant-deploiement`

---

## Objectif

Qu'avant de déployer, une commande dise **oui ou non** si le déploiement peut partir — cible,
identité, état du dépôt, images, et surtout : **le cluster est-il bien configuré pour ne pas
abandonner les tours en cours ?**

---

## Pourquoi

Trois faits, déjà payés :

1. **Un seul environnement, et c'est la production.** Le namespace s'appelle
   `claude-gateway-staging` et le profil Spring `staging`, mais il sert `portal.ng-itconsulting.com`
   avec de vrais utilisateurs. Un nom trompeur invite à déployer « pour voir ».
2. **Un déploiement peut emporter un tour d'agent.** SF-84-08 fait drainer le pod — mais ce
   drainage repose sur cinq réglages, et un `kubectl apply` depuis un dépôt qui ne les porte pas
   les **retirerait du cluster** sans que rien ne le signale. C'est exactement ce qui est arrivé à
   F-77 : le correctif appliqué à la main n'existait dans aucun fichier, et le déploiement suivant
   l'aurait effacé.
3. **Les trois images doivent porter le même tag.** Le 2026-09-22, le frontend n'avait pas été
   reconstruit : `ImagePullBackOff`. Depuis F-142 il y a une **troisième** image
   (`diagram-renderer`), donc une occasion de plus d'en oublier une.

Le contrôle correspondant se fait aujourd'hui de tête, en relisant `docs/DEPLOYMENT.md`. Le même
raisonnement tenu à la main avant chaque vague avait déjà conclu « voie libre » alors que trois
sessions volaient (leçon SF-SP-01) — d'où un script, vérifiable et rejouable à l'identique.

---

## Comportement attendu

### Cas nominal

`scripts/preflight-deploy.sh` est lancé depuis le dépôt. Il ne modifie **rien** — ni le dépôt, ni
le cluster, ni AWS — et rend un verdict unique :

| Verdict | Sortie | Sens |
|---|---|---|
| `GO` | 0 | Tous les contrôles bloquants sont verts : le déploiement peut partir |
| `NO-GO` | 1 | Au moins un contrôle bloquant est rouge |
| usage | 2 | Option inconnue ou valeur invalide |
| `INDÉTERMINÉ` | 4 | L'état n'est pas évaluable (hors dépôt, outil absent, AWS ou cluster injoignable) |

Contrôles **bloquants** :

| Code | Contrôle | Pourquoi |
|---|---|---|
| G1 | Le profil AWS est bien celui attendu (`legalcase-terraform`) et l'identité répond | Déployer avec le mauvais compte, c'est déployer ailleurs |
| G2 | Le contexte `kubectl` pointe le cluster attendu (`legalcase-shared`) et le namespace existe | Même raison, côté cluster |
| G3 | L'arbre de travail est propre et `HEAD` est **contenu dans `origin/main`** | Le tag d'image vaut `staging-$(git rev-parse --short HEAD)` : déployer un HEAD qui n'est pas dans `main` met en production un commit que personne n'a revu |
| G4 | Le manifeste du dépôt porte le drainage (SF-84-08) : `terminationGracePeriodSeconds`, `maxUnavailable: 0`, `shutdown: graceful`, `turn-drain-seconds` | **C'est le cœur du drapeau** : sans ces réglages, l'`apply` retire le drainage du cluster et le déploiement se remet à tuer les tours |
| G5 | Les **trois** images portent le tag du commit dans ECR (`backend`, `frontend`, `diagram-renderer`) | L'oubli se paie par un `ImagePullBackOff` |

Contrôles **informatifs** (jamais de NO-GO) :

| Code | Information |
|---|---|
| I1 | Ce qui tourne déjà : nombre de pods `Running`, et **tout pod déjà `Terminating`** (un déploiement est peut-être en cours) |
| I2 | Rappel de la durée : avec le drainage, un rollout peut prendre plusieurs minutes de plus |
| I3 | Rappel **qu'un tour en cours reste possible** : la garde vérifie la configuration, pas l'instant. Si un tour long est connu, prévenir le PO et le laisser choisir le moment |

### Cas d'erreur

| Situation | Comportement | Sortie |
|---|---|---|
| `aws` ou `kubectl` absent du `PATH` | `INDÉTERMINÉ`, l'outil manquant est nommé | 4 |
| Identité AWS non résolue (session expirée) | `INDÉTERMINÉ` | 4 |
| Cluster injoignable | `INDÉTERMINÉ` | 4 |
| Profil AWS ≠ profil attendu | `NO-GO` | 1 |
| Contexte `kubectl` ≠ cluster attendu | `NO-GO` | 1 |
| Arbre sale, ou `HEAD` absent de `origin/main` | `NO-GO` | 1 |
| Un réglage de drainage manquant dans le dépôt | `NO-GO`, le réglage manquant est nommé | 1 |
| Une des trois images absente d'ECR | `NO-GO`, l'image et le tag sont nommés | 1 |
| Lancé hors d'un dépôt Git | `INDÉTERMINÉ` | 4 |
| Option inconnue | usage | 2 |

---

## Critères d'acceptation

- [ ] `scripts/preflight-deploy.sh` existe, est exécutable, et son en-tête sert d'aide (`--help`).
- [ ] Le script est **strictement en lecture seule** : aucun `apply`, `create`, `delete`, `push`,
      `commit`, `checkout`, `stash`. Vérifié par le test.
- [ ] Les quatre codes de sortie (0 / 1 / 2 / 4) sont respectés, un par situation.
- [ ] G4 échoue si **l'un** des quatre réglages de drainage manque du dépôt — le test le prouve
      réglage par réglage, chacun avec son contrôle négatif.
- [ ] G5 nomme l'image et le tag manquants, et couvre bien les **trois** images.
- [ ] Le script s'exécute sans `aws`/`kubectl` réels dans son test : les binaires sont substituables
      par le `PATH`, aucun appel réseau n'est nécessaire pour tester.
- [ ] Aucun secret n'est lu, affiché ni journalisé : le script ne touche ni Secrets Manager, ni le
      secret `backend-secrets`, ni aucune clé.
- [ ] `docs/DEPLOYMENT.md` porte une **Étape 0** qui appelle la garde et énonce la règle de
      déploiement (un seul environnement = production ; un seul déploiement en fin de vague ;
      aucune vague ne déploie d'elle-même).
- [ ] `ai-skills/autonomous-delivery-wave.md` renvoie à l'Étape 0 en phase 4.
- [ ] `scripts/preflight-deploy.test.sh` couvre chaque verdict, chaque code de sortie, et chaque
      contrôle bloquant **avec son contrôle négatif**.

---

## Périmètre

### Hors scope (explicite)

- **Déployer.** La garde dit « on peut », elle ne fait rien partir. Le déploiement reste la suite
  de commandes de `docs/DEPLOYMENT.md`, lancée par un humain.
- **Détecter à la seconde près qu'un tour tourne.** Cela demanderait de lire `runner_audit` dans
  RDS (instance privée : il faudrait créer un Job dans le cluster, donc écrire) ou d'exposer un
  compte de tours vivants sur une route publique (`/actuator/health**` est `permitAll`). Les deux
  coûtent plus que ce qu'ils rapportent **depuis que SF-84-08 draine** : la garde vérifie donc que
  le drainage est en place, et rappelle (I3) de demander au PO en cas de tour long connu.
- Bloquer la CI. Les workflows GitHub ne sont pas modifiés.
- Le frontend et `diagram-renderer` côté arrêt : sans état de tour.

---

## Contraintes de validation

| Paramètre | Défaut | Surchargeable | Règle |
|---|---|---|---|
| `AWS_PROFILE` | `legalcase-terraform` | oui (env / `--profile`) | Doit correspondre à `docs/DEPLOYMENT.md` |
| `AWS_REGION` | `eu-west-3` | oui (env) | — |
| `CLUSTER` | `legalcase-shared` | oui (`--cluster`) | Le contexte `kubectl` doit le contenir |
| `NAMESPACE` | `claude-gateway-staging` | oui (`--namespace`) | Nom legacy assumé ; c'est la production |
| `BASE_REF` | `origin/main` | oui (`--base`) | `HEAD` doit y être contenu |
| `TAG` | `staging-$(git rev-parse --short HEAD)` | oui (`--tag`) | Le même pour les trois images |

Options de conduite : `--no-ecr` (sauter G5, avant le build des images), `--no-git` (sauter G3),
`--quiet` (n'imprimer que le verdict), `--help`.

---

## Technique

### Endpoint(s) / Tables / Migration

Aucun. Aucune ligne de code applicatif : un script d'exploitation et de la documentation.

### Fichiers touchés

| Fichier | Nature |
|---|---|
| `scripts/preflight-deploy.sh` | **nouveau** — la garde |
| `scripts/preflight-deploy.test.sh` | **nouveau** — son test d'intégration |
| `docs/DEPLOYMENT.md` | **Étape 0** + règle de déploiement |
| `ai-skills/autonomous-delivery-wave.md` | phase 4 renvoie à l'Étape 0 |

---

## Préoccupations transversales

| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| Auth / Principal | Non | Aucun code applicatif. |
| Contexte tenant | Non | Aucun accès aux données ; le script ne lit aucune base. |
| Plans / limites | Non | — |
| Navigation / routing | Non | Aucun front. |
| **Procédure de déploiement** | **Oui** | `docs/DEPLOYMENT.md` (Étape 0 ajoutée, étapes 1→7 inchangées), `ai-skills/autonomous-delivery-wave.md` (phase 4). Les workflows `.github/workflows/{backend,frontend}.yml` ne sont **pas** modifiés : la garde est un contrôle humain, pas une porte de CI. |

---

## Plan de test

`scripts/preflight-deploy.test.sh` monte un dépôt Git jetable et substitue `aws`, `kubectl` et `gh`
par des bouchons placés en tête de `PATH`. Chaque cas NO-GO est accompagné de son **contrôle
négatif** : le même dépôt privé du défaut doit rendre `GO`.

- [ ] Tout vert → `GO`, sortie 0.
- [ ] Mauvais profil AWS → `NO-GO`, sortie 1 (+ contrôle négatif).
- [ ] Mauvais contexte `kubectl` → `NO-GO`, sortie 1 (+ contrôle négatif).
- [ ] Namespace absent → `NO-GO`, sortie 1.
- [ ] Arbre sale → `NO-GO`, sortie 1 (+ contrôle négatif).
- [ ] `HEAD` absent de `origin/main` → `NO-GO`, sortie 1 (+ contrôle négatif).
- [ ] `terminationGracePeriodSeconds` absent du manifeste → `NO-GO`, réglage nommé.
- [ ] `maxUnavailable: 0` absent → `NO-GO`, réglage nommé.
- [ ] `shutdown: graceful` absent de `application.yml` → `NO-GO`, réglage nommé.
- [ ] `turn-drain-seconds` absent → `NO-GO`, réglage nommé.
- [ ] Une image absente d'ECR (chacune des trois) → `NO-GO`, image et tag nommés.
- [ ] `--no-ecr` saute G5 et rend `GO` malgré des images absentes.
- [ ] `aws` absent du `PATH` → `INDÉTERMINÉ`, sortie 4.
- [ ] `kubectl` absent du `PATH` → `INDÉTERMINÉ`, sortie 4.
- [ ] Identité AWS en échec → `INDÉTERMINÉ`, sortie 4.
- [ ] Hors dépôt Git → `INDÉTERMINÉ`, sortie 4.
- [ ] Option inconnue → sortie 2 ; `--help` → sortie 0 et affiche l'aide.
- [ ] Un pod déjà `Terminating` → signalé (I1) **sans** inverser le verdict.
- [ ] **Non-destructivité** : après une exécution complète, le dépôt jetable est bit pour bit
      identique (statut, `HEAD`, pile de remise), et aucun bouchon n'a reçu de verbe d'écriture.

### Isolation workspace / `user_id`

- [x] Non applicable — le script n'accède à aucune donnée applicative.

---

## Dépendances

### Subfeatures bloquantes

- `SF-84-08` (le drainage) — **done**. G4 vérifie précisément les réglages qu'elle pose.

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **Vérifier la propriété, pas l'instant.** « Un déploiement ne tue pas les tours en cours » se
  vérifie de deux façons : échantillonner l'instant (« y a-t-il un tour à la seconde près ? ») ou
  vérifier la propriété (« ce déploiement est-il de ceux qui laissent finir les tours ? »).
  L'échantillon est fragile — un tour peut démarrer entre le contrôle et l'`apply` — et coûte cher
  à obtenir ici (RDS privé, ou exposition publique d'un compte). La propriété est déterministe et
  se teste hors ligne. On vérifie la propriété, et on rappelle l'humain sur le reste.
- **Pourquoi `HEAD` contenu dans `origin/main`, et pas « égal ».** `main` peut avancer pendant
  qu'on déploie un commit un peu plus ancien ; ce qui est inacceptable, c'est de mettre en
  production un commit **absent** de `main`.
- **Pourquoi pas une porte de CI.** Le dépôt n'a pas de déploiement automatique sur `main`
  (mémoire projet `ci-deploy-not-configured`) ; ajouter une porte à un chemin que personne
  n'emprunte donnerait une garantie fictive. La garde est là où se prend la décision : dans le
  terminal de celui qui déploie.
