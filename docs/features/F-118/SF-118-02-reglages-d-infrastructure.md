# Mini-spec — [F-118 / SF-118-02] Les réglages d'infrastructure, et une garde qui les tient

---

## Identifiant

`F-118 / SF-118-02`

## Feature parente

`F-118` — Effort adaptatif et réglages d'infrastructure

## Statut

`in-progress`

## Date de création

2026-09-26

## Branche Git

`feat/SF-118-02-reglages-d-infrastructure`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Clore le volet « infrastructure » de F-118 : acter les réglages de tenue en charge **déjà appliqués
aux manifestes par l'orchestrateur/PO** (pool base, `MaxRAMPercentage`, `minReplicas`, limite CPU,
`proxy-buffering`), trancher par écrit les deux points que l'incident RDS du 2026-09-16 a
**réorientés**, et poser une **garde de dépôt** (test qui relit `k8s/base/**`) pour qu'aucune de ces
valeurs — ni l'invariant qui les lie — ne reparte à la dérive sans échouer avant le déploiement.

---

## Comportement attendu

### Cas nominal

> Description précise du flux principal (entrée → traitement → sortie).

1. **État acté** (aucune valeur changée par cette subfeature ; elles viennent de `708c1475` puis du
   correctif d'incident `cc37318c`) :

   | Réglage | Fichier | Valeur | Raison |
   |---------|---------|--------|--------|
   | Pool Hikari | `k8s/base/backend/configmap.yaml` | `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE: "10"`, `MINIMUM_IDLE: "2"` | RDS **partagée** avec legalcase (cf. A1) |
   | Heap JVM | `k8s/base/backend/configmap.yaml` | `JAVA_TOOL_OPTIONS: "-XX:MaxRAMPercentage=75.0"` | sans lui, ~512 Mo de heap sur une limite de 2 Gi |
   | Mise à l'échelle | `k8s/base/backend/hpa.yaml` | `minReplicas: 2`, `maxReplicas: 3`, CPU 70 % | la charge est de l'**attente réseau** : un pod sature ses flux SSE sans monter en CPU |
   | Capacité pod | `k8s/base/backend/deployment.yaml` | `replicas: 2`, CPU `2000m`, mémoire `2Gi` | ~16 workers `@Scheduled` + Tomcat + flux SSE |
   | Fluidité SSE | `k8s/base/ingress/ingress.yaml` | `nginx.ingress.kubernetes.io/proxy-buffering: "off"` | sans elle, nginx tamponne et le mot à mot (F-116) se fige |

2. **Ce que livre cette subfeature** : un test de dépôt `KubernetesCapacityManifestTest` (même
   patron que `KubernetesProbeManifestTest` de F-77) qui relit les quatre manifestes et **échoue**
   si l'un des réglages disparaît, ou si l'**invariant de capacité base** est franchi.
3. **L'invariant de capacité base** — le cœur de la garde : la RDS est partagée, le budget de
   connexions côté claude-gateway est de **30**. Donc
   `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE × hpa.maxReplicas ≤ 30`. C'est exactement la règle
   que le 2026-09-16 a violée en silence (`30 × 4 = 120`) : deux fichiers séparés, chacun
   raisonnable seul, et personne pour lire le produit des deux.
4. Le test tourne dans la suite backend ordinaire (`mvn -pl backend test`), donc **avant** tout
   déploiement, et il localise la racine du dépôt en remontant les parents (même méthode que F-77) :
   une garde qui ne trouve plus sa cible échoue bruyamment plutôt que de passer.

### Cas d'erreur

> Lister tous les cas d'erreur identifiés.

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Quelqu'un remonte le pool à 30 sans toucher l'HPA | `KubernetesCapacityManifestTest` échoue en nommant le produit (`30 × 3 = 90 > 30`) | n/a (build) |
| Quelqu'un remonte `maxReplicas` à 4 ou plus sans baisser le pool | même test, même message | n/a (build) |
| `JAVA_TOOL_OPTIONS` retiré ou `MaxRAMPercentage` hors de `[50, 85]` | test en échec (en dessous : heap gâchée ; au-dessus : plus de marge pour le hors-tas, OOMKill) | n/a (build) |
| `minReplicas` ramené à 1 | test en échec : un pod seul ne scale pas sur une charge I/O, et un rollout coupe le service | n/a (build) |
| `proxy-buffering` retiré ou mis à `"on"` | test en échec : le flux SSE se figerait pour **tous** les utilisateurs | n/a (build) |
| `replicas` du Deployment < `minReplicas` de l'HPA | test en échec : incohérence qui fait osciller le cluster au déploiement | n/a (build) |
| Manifeste déplacé/renommé | le test lève `IllegalStateException` (jamais un « vert » silencieux) | n/a (build) |

---

## Critères d'acceptation

> Chaque critère est vérifiable. Pas d'ambiguïté.

- [ ] Le test relit `configmap.yaml`, `hpa.yaml`, `deployment.yaml`, `ingress/ingress.yaml` depuis la
      racine du dépôt retrouvée par remontée de parents
- [ ] Il vérifie `pool × maxReplicas ≤ 30` et nomme les deux termes dans son message d'échec
- [ ] Il vérifie `MINIMUM_IDLE ≤ MAXIMUM_POOL_SIZE`
- [ ] Il vérifie la présence de `-XX:MaxRAMPercentage=` et sa valeur dans `[50, 85]`
- [ ] Il vérifie `minReplicas ≥ 2` et `maxReplicas ≥ minReplicas`
- [ ] Il vérifie `deployment.spec.replicas ≥ hpa.minReplicas`
- [ ] Il vérifie la limite CPU du conteneur backend `≥ 1000m` et la présence d'une limite mémoire
- [ ] Il vérifie `nginx.ingress.kubernetes.io/proxy-buffering == "off"` sur l'ingress applicatif
- [ ] Un manifeste introuvable fait échouer le test (pas de faux vert)
- [ ] **Aucune valeur de manifeste n'est modifiée par cette subfeature** : le diff des `k8s/**`
      se limite à des commentaires de traçabilité
- [ ] Suite backend verte

---

## Périmètre

### Hors scope (explicite)

- **Changer une valeur d'infra.** Les valeurs sont celles appliquées par le PO/orchestrateur ; cette
  subfeature les gèle, elle ne les arbitre pas à nouveau.
- **Déployer.** Aucun `kubectl`, aucun rollout : l'orchestrateur déploie une seule fois, en fin de
  vague.
- **La métrique « requêtes par pod » sur l'HPA** (cf. A2) — exige un adaptateur de métriques
  personnalisées, reporté en V2 avec le volet monitoring (CLAUDE.md §C3).
- **Le dimensionnement de Tomcat** (`server.tomcat.threads.max`) : non mesuré, et un plafond posé à
  l'aveugle dégraderait plus qu'il ne protège. Tracé en risque résiduel.
- `.github/**` : hors périmètre des agents.
- Frontend : aucun (réglage d'exploitation, aucun écran).

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| Budget de connexions RDS côté claude-gateway | `30` | Constante du test (`RDS_CONNECTION_BUDGET`) ; ne se relève qu'avec la capacité RDS partagée |
| Plancher `MaxRAMPercentage` | `50` | En dessous, la limite mémoire de 2 Gi est gâchée |
| Plafond `MaxRAMPercentage` | `85` | Au-dessus, plus de marge hors-tas (métaspace, threads, buffers) → OOMKill |
| Plancher limite CPU | `1000m` | ~16 workers `@Scheduled` + Tomcat + flux SSE |

---

## Contraintes de validation

| Champ | Obligatoire | Format / Valeurs autorisées | Normalisation |
|-------|-------------|----------------------------|---------------|
| `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` | Oui | entier ≥ 2 | lu en chaîne dans le configmap, converti par le test |
| `SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE` | Oui | entier ≥ 0, ≤ pool | idem |
| `JAVA_TOOL_OPTIONS` | Oui | contient `-XX:MaxRAMPercentage=<50..85>` | valeur décimale acceptée (`75.0`) |
| `hpa.minReplicas` / `hpa.maxReplicas` | Oui | entiers, `2 ≤ min ≤ max` | — |
| limite CPU backend | Oui | `<n>m` ou `<n>` (cœurs) | convertie en millicores par le test |
| `proxy-buffering` | Oui | exactement `"off"` | — |

---

## Technique

### Endpoint(s)

Aucun.

### Tables impactées

Aucune.

### Migration Liquibase

- [ ] Oui
- [x] Non applicable — aucune donnée, aucun schéma.

### Composants Angular (si applicable)

Aucun — réglage d'exploitation sans écran. (CLAUDE.md « subfeature backend sans subfeature frontend » :
non applicable, la feature n'a pas d'UI.)

### Fichiers touchés

- `backend/src/test/java/fr/claudegateway/health/KubernetesCapacityManifestTest.java` — **nouveau**,
  la garde.
- `k8s/base/backend/hpa.yaml`, `k8s/base/backend/configmap.yaml` — commentaires : renvoi vers la
  garde, pour que le prochain lecteur sache où la règle est vérifiée. **Aucune valeur touchée.**
- `docs/features/F-118/SF-118-02-reglages-d-infrastructure.md` — cette mini-spec.

---

## Préoccupations transversales

Aucun déclencheur coché : ni Auth/Principal, ni contexte tenant, ni Plans/limites, ni
Navigation/routing. Aucun code de production n'est modifié — la livraison est un test et des
commentaires. L'isolation `user_id`/`workspace_id` n'est ni touchée ni contournée (aucun accès
donnée).

---

## Plan de test

### Tests unitaires

- [ ] `KubernetesCapacityManifestTest` — le pool et son minimum au repos sont cohérents
- [ ] `KubernetesCapacityManifestTest` — `pool × maxReplicas ≤ 30` (l'incident du 2026-09-16)
- [ ] `KubernetesCapacityManifestTest` — `MaxRAMPercentage` présent et dans `[50, 85]`
- [ ] `KubernetesCapacityManifestTest` — `minReplicas ≥ 2`, `maxReplicas ≥ minReplicas`
- [ ] `KubernetesCapacityManifestTest` — `deployment.replicas ≥ hpa.minReplicas`
- [ ] `KubernetesCapacityManifestTest` — limite CPU ≥ 1000m, limite mémoire présente
- [ ] `KubernetesCapacityManifestTest` — `proxy-buffering: off` sur l'ingress applicatif

### Tests d'intégration

- [x] Non applicable — aucun composant Spring, aucun endpoint. La garde est un test de dépôt pur,
      exécuté par la suite backend (donc avant tout déploiement).

### Isolation workspace

- [x] Non applicable — raison : aucune donnée lue ou écrite.

---

## Dépendances

### Subfeatures bloquantes

- `SF-118-01` — Done (PR #621).
- `SF-118-03` — Done (PR #623).

### Questions ouvertes impactées

- [ ] Aucune de `docs/OPEN_QUESTIONS.md`. (La métrique de mise à l'échelle non-CPU relève du volet
      monitoring V2 déjà tranché en CLAUDE.md §C3 : CloudWatch en V1, Prometheus/Grafana en cible.)

---

## Notes et décisions

- **A1 — le pool ne remonte pas au-dessus de 10** (le cadrage disait « > 10 »). Réversible, tranché
  par l'**incident du 2026-09-16** : la RDS est partagée avec legalcase ; `30 × 4 pods ≈ 120`
  connexions ont épuisé les slots, mis les pods en crash-loop Liquibase **et** fait tomber legalcase.
  Le besoin réel derrière « pool > 10 » était d'éviter l'attente de connexion ; il est aujourd'hui
  couvert par `maxReplicas 3` (3 × 10 connexions utiles) et par le fait que les ~16 workers
  `@Scheduled` partagent **un seul** thread d'ordonnancement. La règle devient explicite et testée
  plutôt que commentée.
- **A2 — l'HPA reste sur CPU seul.** La métrique « requêtes par pod » exige un adaptateur de
  métriques personnalisées (Prometheus adapter ou CloudWatch adapter), c'est-à-dire précisément ce
  que CLAUDE.md §C3 reporte en V2. Ce qui couvre le trou en V1 : `minReplicas: 2` (le pod n'est
  jamais seul), et le refus **explicite** côté application quand un pod sature ses flux
  (`error: stream_busy`, F-70/SF-70-01) — une saturation qui se dit, au lieu d'une attente muette.
  Risque résiduel assumé et tracé ci-dessous.
- **A3 — cette subfeature ne change aucune valeur.** Les réglages ont été appliqués par
  l'orchestrateur/PO comme le stipulait le cadrage ; ce qui manquait n'était pas la valeur, c'était
  la **mini-spec** (artefact d'étape 1) et le moyen d'empêcher la dérive. Le dépôt n'avait aucun
  endroit où le produit `pool × maxReplicas` était lu.
- **A4 — aucun déploiement.** L'orchestrateur déploie une seule fois en fin de vague (consigne de
  vague). La garde vit dans la suite de tests, elle ne suppose aucun accès cluster.
- **Risque résiduel** : Tomcat garde son plafond de threads par défaut (200) face à un pool de 10.
  Sous une pointe HTTP inhabituelle, l'attente porterait sur l'acquisition de connexion plutôt que
  sur un refus net. Non traité ici faute de mesure ; à instrumenter avec le volet monitoring V2.
