# Mini-spec — [F-186 / SF-186-01] Les journaux du backend dans CloudWatch

## Identifiant

`F-186 / SF-186-01`

## Feature parente

`F-186` — Journaux de la gateway dans CloudWatch (recommandation 5 de l'audit J+7 du 2026-10-10, validée par le PO)

## Statut

`in-progress`

## Date de création

2026-10-10

## Branche Git

`feat/SF-186-01-journaux-cloudwatch` (claude-gateway, documentation et requêtes) ; `fix/fluent-bit-claude-gateway` (legalCase, manifeste Fluent Bit partagé)

---

## Objectif

Conserver 30 jours les journaux des pods `claude-gateway-staging` dans CloudWatch, pour compter refus, replis, raisonnements écartés et compactions au-delà de la vie d'un pod.

---

## Comportement attendu

### Cas nominal

1. **Cause constatée** : le DaemonSet Fluent Bit du cluster partagé `legalcase-shared` (namespace `amazon-cloudwatch`, manifeste versionné dans `legalCase/k8s/system/fluent-bit.yaml`) ne garde que les namespaces `production` et `staging`. Le filtre `grep` jette le nôtre, `claude-gateway-staging`. Les journaux de la gateway ne vivent donc que dans les pods, et disparaissent à chaque déploiement.
2. **Acheminement dédié**, sans toucher au flux de legalcase :
   - un filtre `rewrite_tag`, placé **avant** le `grep`, retague les enregistrements du namespace `claude-gateway-staging` en `cgw.*` (sans garder l'original) ;
   - ils échappent ainsi au `grep` de legalcase ;
   - une sortie `cloudwatch_logs` `Match cgw.*` les écrit dans **`/aws/eks/legalcase-shared/claude-gateway`** (flux préfixé `cgw-`).
3. **Groupe de journaux** créé une fois (`auto_create_group false` reste la règle du manifeste), avec une **rétention de 30 jours**. Le groupe de legalcase reste à 7 jours, inchangé.
4. **Requêtes de mesure** versionnées dans `docs/ops/cloudwatch-requetes-forge.md` (Logs Insights) :
   - refus du fournisseur (`stop_reason=refusal`) ;
   - tours servis par un modèle de repli ;
   - raisonnement écarté (`thinking_dropped`) ;
   - compactions (réussies, ignorées, sans résumé) ;
   - plafond d'étapes atteint.
   Chaque requête est vérifiée sur les données réelles après le déploiement.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Groupe absent | Fluent Bit n'écrit pas (`auto_create_group false`) : le groupe est créé **avant** l'application du manifeste | — |
| Fluent Bit en échec après rechargement | Retour au manifeste précédent (`kubectl apply` de la version d'avant) ; legalcase est vérifié en premier | — |
| Volume inattendu | Mesure à J+1 ; au repos, environ 175 Ko/h pour toute la gateway, soit moins de 2 $ par mois | — |

---

## Critères d'acceptation

- [ ] Les journaux du backend (les deux pods), du frontend et du renderer arrivent dans `/aws/eks/legalcase-shared/claude-gateway` en moins de 1 minute.
- [ ] Le groupe de legalcase continue de recevoir `production` et `staging`, et **rien** de `claude-gateway-staging`.
- [ ] Rétention de 30 jours sur le nouveau groupe.
- [ ] Les requêtes de `docs/ops/cloudwatch-requetes-forge.md` s'exécutent et rendent un résultat, éventuellement vide.
- [ ] Le manifeste versionné (legalCase) et le cluster sont identiques.

---

## Plan de test

- **Avant** : comptage des flux du groupe legalcase sur 10 min (référence).
- **Après application** :
  - `aws logs filter-log-events` sur le nouveau groupe : présence d'une ligne émise par le backend après le redémarrage de Fluent Bit ;
  - le groupe legalcase reçoit toujours des événements `production` / `staging` ;
  - aucun événement `claude-gateway-staging` dans le groupe legalcase.
- **Requêtes** : chaque requête Logs Insights est lancée une fois, et sa syntaxe est validée.
- **Isolation utilisateur** : sans objet. Les journaux n'exposent pas de contenu de tour (règle existante), et l'accès au groupe se fait par IAM, comme pour legalcase.
- Pas de test unitaire : changement d'infrastructure seulement, sans code applicatif.

---

## Composants impactés

- `legalCase/k8s/system/fluent-bit.yaml` (ConfigMap `fluent-bit-config`) : un filtre `rewrite_tag` et une sortie de plus.
- AWS : groupe `/aws/eks/legalcase-shared/claude-gateway`, rétention 30 jours. Le rôle IRSA `legalcase-shared-fluent-bit-role` (`CloudWatchAgentServerPolicy`) suffit.
- `claude-gateway/docs/ops/cloudwatch-requetes-forge.md` (nouveau).
- Aucun code, aucune table, aucun endpoint.

## Préoccupations transversales

Aucune côté application. **Infrastructure partagée** : le DaemonSet Fluent Bit sert aussi legalcase. Le changement est additif (le `grep` de legalcase est inchangé) et vérifié des deux côtés.

---

## Périmètre

### Hors scope (explicite)

- Métriques CloudWatch ou alarmes (cible V2+, C3 de CLAUDE.md).
- Journaux structurés JSON : on garde le format actuel.
- Nouveaux points de journalisation : les lignes nécessaires existent déjà (`AnthropicAgentProvider`, `AtelierCompactionService`).
