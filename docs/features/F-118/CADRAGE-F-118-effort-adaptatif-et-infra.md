# F-118 — Effort adaptatif et réglages d'infrastructure

> Cadrage du 2026-09-14, sur l'audit performance. **Cadrage seul : livraison sur go du PO (donné).**

## 1. Le constat
Deux gisements de rapidité **au-delà** du streaming (F-116), sur le **débit** et la **tenue en charge** :
- **Effort de réflexion plat** : la boucle maison appelle le modèle avec `effort=high` + raisonnement
  adaptatif à **chaque** itération (`AtelierChatService:452`). Réfléchir « fort » pour enchaîner un
  `read_file` ou un `ls` coûte des secondes inutiles. Gain plausible sur la durée totale : **−20 à 40 %**.
- **Réglages d'infra** au défaut : pool de connexions base Hikari **=10** (aucune config), mémoire JVM
  **~512 Mo sur 2 Go** (aucun `MaxRAMPercentage`), mise à l'échelle **sur CPU** alors que la charge est
  de l'**attente réseau** (le pod sature ses 32 flux SSE sans monter en CPU → pas de scale-out),
  `proxy-buffering` non épinglé sur l'ingress.

## 2. Ce qu'on livre

### SF-118-01 — L'effort s'adapte à l'étape
- Premier tour d'une demande : effort **normal** (la réflexion sert). Étapes de **continuation**
  (enchaîner un outil, relire) : effort **réduit** par défaut. Réglable (`APP_ATELIER_EFFORT` +
  politique par étape), sans jamais dégrader une vraie tâche de raisonnement.
- Tests : une étape de continuation part avec l'effort réduit ; une demande neuve garde l'effort normal ;
  drapeau de repli.

### SF-118-02 — Les réglages d'infrastructure (avec l'orchestrateur/PO)
- **Pool base** : `spring.datasource.hikari` dimensionné (>10) selon Tomcat et les workers `@Scheduled`.
- **Mémoire JVM** : `-XX:MaxRAMPercentage=75`.
- **Mise à l'échelle** : min replicas ≥ 2 et/ou métrique « requêtes par pod » plutôt que CPU seul ;
  relever la limite CPU face aux ~11 workers planifiés.
- **Ingress** : épingler `proxy-buffering: off` pour la fluidité SSE de tous.
- Ces réglages touchent `application.yml` (config) et `k8s/base/**` : le volet k8s est appliqué **par
  l'orchestrateur/PO** (les agents ne modifient pas `.github/`, et les manifestes k8s hors code
  applicatif restent une action supervisée).

## 3. Découpage & ordre
SF-118-01 (effort, code applicatif, livrable par un agent) d'abord ; SF-118-02 (infra) tracée et
appliquée avec le PO. Aucune migration.

## 4. Préoccupations transversales
- **Plans/coût** : l'effort réduit **baisse** le coût des étapes simples. **Auth/tenant** : non.
- Composants : `AtelierChatService`, `AtelierProperties`, `application.yml`, `k8s/base/{deployment,hpa,configmap}.yaml`, `k8s/base/ingress/ingress.yaml`.

## 5. Hors périmètre
Le streaming (F-116), la gestion du contexte (F-117), le chemin Managed Agents.
