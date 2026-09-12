# Cadrage — F-77 — La santé du service ne dépend plus du courrier

**Date** : 2026-09-12
**Source de vérité** : `docs/PRODUCT_SPEC.md`, ligne F-77. Les faits y sont **observés**, pas
supposés : ils ne sont ni rediscutés ni « améliorés » ici.

---

## Ce qui s'est passé (recopié de PRODUCT_SPEC, non discuté)

Le 2026-09-12 à 09 h 07 (heure de Paris), le portail a rendu **503** pendant une vingtaine de
minutes. Quatre pods backend en redémarrage permanent, processeur à 273 %. La cause n'était pas
dans le code livré ce jour-là :

1. Le relais SMTP (Brevo) a ralenti — jusqu'à **70 secondes** pour répondre.
2. Les **trois** sondes Kubernetes (`liveness`, `readiness`, `startup`) interrogeaient
   `/api/actuator/health`, la santé **agrégée**, qui **inclut** `MailHealthIndicator`.
3. Le délai d'attente des sondes était d'**une seconde**.

Chaque sonde échouait, Kubernetes tuait le conteneur, qui redémarrait et échouait de nouveau.
**Un service dont personne n'envoyait de courrier a été déclaré mort parce que son serveur de
courrier était lent.**

Le correctif a été **appliqué à la main sur le cluster** pour rétablir le service. Il n'existe
nulle part dans le dépôt : le prochain `kubectl apply` depuis `k8s/` le **perdrait**, et la panne
reviendrait sans prévenir. **F-77 ne répare rien : F-77 rend le correctif reproductible.**

---

## Ce qui existe déjà (constaté dans le code, pas sur le cluster — aucun `kubectl` n'a été lancé)

| Fait | Où | Conséquence |
|---|---|---|
| Les trois sondes pointent sur l'agrégat `/api/actuator/health`, sans `timeoutSeconds` (donc **1 s**, le défaut Kubernetes) | `k8s/base/backend/deployment.yaml` l.46-63 | C'est exactement la configuration qui a provoqué la panne. C'est **elle** que F-77 corrige. |
| `management.endpoint.health.probes.enabled: true` n'est posé que dans le profil `staging` | `backend/src/main/resources/application-staging.yml` | Les groupes existent **en production** (la réponse de `/api/actuator/health` portait `"groups":["liveness","readiness"]`) — mais par un réglage de profil, pas par un contrat. |
| Le groupe `readiness` auto-configuré par Spring contient **`readinessState` et rien d'autre** (vérifié dans le bytecode de `AvailabilityProbesHealthEndpointGroups` 3.5.0 : `liveness` → `livenessState`, `readiness` → `readinessState`) | dépendance Spring Boot 3.5.0 | **La base n'est PAS dans `readiness` par défaut.** Un pod dont la base est tombée se déclarerait prêt. Il faut l'y mettre, explicitement. |
| `spring.mail.*` ne pose **aucun** délai d'attente | `application.yml` §`spring.mail` | JavaMail sans `connectiontimeout`/`timeout`/`writetimeout` attend **indéfiniment**. Les 70 s observées ne sont pas un plafond : c'est ce qu'a duré l'aller-retour ce jour-là. |
| `/actuator/health` et `/actuator/health/**` sont publics | `SecurityConfig` l.70 | Les sous-chemins de groupe sont déjà joignables sans jeton : rien à ouvrir. |
| Le déploiement backend n'est **pas** déclenché par un push (`on: workflow_dispatch` seulement) | `.github/workflows/backend.yml` | Merger F-77 ne déploie rien. Le portage prend effet au **prochain déploiement volontaire** — c'est voulu, et c'est dit dans la PR. |

---

## Les trois décisions de conception

### D1 — Les sondes lisent les groupes dédiés, jamais l'agrégat

`liveness` → `/api/actuator/health/liveness`, `readiness` → `/api/actuator/health/readiness`.
`timeoutSeconds: 5` sur les trois : une seconde ne laisse aucune marge à une base qui répond en
800 ms.

### D2 — `readiness` inclut la base, jamais le courrier

Le défaut de Spring (`readinessState` seul) est **insuffisant** : un pod sans base ne peut pas
servir une seule requête utile, et se déclarerait pourtant prêt. Le groupe est donc **écrit à la
main** : `readinessState,db`. Le courrier n'y est pas, et `liveness` ne contient que
`livenessState` — l'état du processus, rien de ce qui est joignable par le réseau.

Tout est posé dans `application.yml` — **pas** laissé à un défaut de Spring Boot. Un défaut qui
change de version rouvrirait la panne, silencieusement, un jour où personne ne regarde.

### D3 — Le vrai correctif de fond : borner l'indicateur de courrier

Sortir le courrier des sondes protège **les sondes**. Cela ne protège pas
`/api/actuator/health` : l'agrégat reste interrogé par tout tableau de bord, toute supervision,
tout `curl` de vérification — y compris **le healthcheck de nos propres workflows CI**
(`backend.yml` l.155). Un `MailHealthIndicator` qui met 70 s les fait tous attendre 70 s.

Donc : `spring.mail.properties.mail.smtp.connectiontimeout|timeout|writetimeout = 5000`.
Au-delà de 5 s, l'indicateur rend `DOWN` — ce qui est **la vérité** (« le SMTP ne répond pas »),
rendue en 5 s au lieu de 70. **Les sondes n'étaient que la victime ; le courrier sans délai
d'attente était la cause.**

---

## Hors périmètre (explicite, aligné sur PRODUCT_SPEC)

- Changer de fournisseur de courrier.
- L'alerte qui aurait prévenu (la panne a été découverte par hasard).
- Toucher au cluster : **aucun `kubectl`**. Le correctif y vit déjà.
- Modifier le contrat visible : aucun endpoint, aucun écran, aucune table ne change.

---

## Découpage

| SF | Titre | Charge |
|---|---|---|
| SF-77-01 | Les sondes lisent les groupes dédiés, le courrier ne peut plus les bloquer | < 1 j |

Une seule subfeature : les trois changements (groupes Spring, sondes Kubernetes, délais SMTP) sont
**le même correctif**. Les séparer livrerait un manifeste qui pointe des groupes dont la
composition n'est pas encore écrite — c'est-à-dire une fenêtre où la panne reste ouverte.
