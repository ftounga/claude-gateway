# Mini-spec — [F-28 / SF-17] Modèle et effort de l'agent, pilotables par session

---

## Identifiant

`F-28 / SF-17`

## Feature parente

`F-28` — Atelier (Claude Code Lite)

## Statut

`ready`

## Date de création

2026-08-26

## Branche Git

`feat/SF-28-17-modele-et-effort-de-session`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Rendre le **modèle** et l'**effort de raisonnement** de l'agent réellement pilotables par configuration,
et passer l'Atelier sur **Claude Opus 5**.

---

## Contexte

Inspection de l'agent provisionné en production (2026-08-26) :

```
modèle : claude-opus-4-8   |   effort : xhigh
```

Trois constats, tous vérifiés :

1. **Le modèle est une génération en arrière.** Le compte a accès à `claude-opus-5` — même tarif
   d'entrée et de sortie qu'`opus-4-8`, capacités supérieures. L'Atelier ne l'utilise pas.
2. **L'effort n'est choisi par personne.** Rien dans le code ne l'envoie : `xhigh` est le défaut de la
   plateforme. C'est le réglage le plus coûteux après `max`, appliqué à toutes les tâches — y compris
   « liste-moi les fichiers ».
3. **La propriété `model` ne sert à rien en pratique.** `ensureBootstrapped` renvoie la configuration
   déjà en base **sans jamais la comparer** à la configuration voulue : changer
   `APP_ATELIER_AGENT_MODEL` sur un déploiement existant n'a **aucun effet**. La propriété donne
   l'illusion d'un levier qui n'existe pas.

C'est **OQ-04**, ouverte depuis l'origine du projet — et le premier levier de marge, devant toute
discussion sur le prix des plans.

---

## Comportement attendu

### Cas nominal

1. Le modèle et l'effort sont **envoyés à chaque ouverture de session**, en surcharge locale
   (`agent_with_overrides`), aux côtés du prompt, des outils et du MCP déjà surchargés.
2. Les deux sont **configurables** ; leur changement prend effet à la session suivante, **sans
   re-provisionner l'agent** ni migrer quoi que ce soit.
3. Le modèle par défaut devient **`claude-opus-5`**.
4. L'effort par défaut reste **`xhigh`** — le réglage effectif d'aujourd'hui : cette subfeature rend le
   levier disponible, elle ne change pas le comportement sans qu'on le décide.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Effort configuré inconnu | Repli sur `xhigh`, trace en journal — un run ne doit pas échouer sur une faute de frappe de configuration |
| Modèle configuré vide | Repli sur le défaut |
| Modèle refusé par le fournisseur | Erreur remontée comme toute erreur fournisseur, avec son statut et son type (SF-30-08) |
| Modèle sans tarif public | La création budgétée est refusée (F-36) : le message doit rester lisible |

---

## Critères d'acceptation

- [ ] Modèle et effort sont transmis à chaque création de session, en surcharge locale
- [ ] Les deux sont configurables ; aucun re-provisionnement d'agent n'est nécessaire
- [ ] Le défaut de modèle est `claude-opus-5`
- [ ] Le défaut d'effort est `xhigh` (comportement actuel préservé)
- [ ] Un effort inconnu retombe sur le défaut sans faire échouer le run
- [ ] Les surcharges existantes (prompt F-34, outils F-33, MCP F-31, délégation F-35) sont **inchangées** et cohabitent
- [ ] Aucune table, aucune migration, aucun endpoint

---

## Périmètre

### Hors scope

- **Choix du modèle par l'utilisateur** dans l'interface : c'est une décision produit et tarifaire
  distincte (un plan Gold sur Opus, un plan inférieur sur un modèle moins cher, par exemple)
- Modèle différent selon la nature de la tâche : suppose de mesurer d'abord
- Effort réduit pour les sous-agents : le roster est `self`, il hérite de la configuration du
  coordinateur ; le différencier demanderait un agent dédié
- **Catalogue de modèles du chat** (F-02) : `ANTHROPIC_MODELS` propose encore `opus-4-8`, `sonnet-5`,
  `haiku-4-5` et ignore `opus-5` — même constat, autre feature, à traiter à part

---

## Contraintes de validation

| Élément | Contrainte |
|---------|-----------|
| Modèle | Identifiant non vide ; défaut `claude-opus-5` |
| Effort | `low`, `medium`, `high`, `xhigh`, `max` ; défaut `xhigh` ; valeur inconnue → défaut |
| Forme envoyée | `model: {id, effort: {type}}` dans la surcharge de session |

---

## Technique

### Endpoint(s)

Aucun.

### Tables impactées / Migration

Aucune.

### Fichiers impactés

| Fichier | Nature |
|---------|--------|
| `atelier/agent/AtelierAgentProperties.java` | + `effort`, défaut de `model` porté à `claude-opus-5` |
| `atelier/agent/AnthropicManagedAgentProvider.java` | Modèle et effort dans la surcharge de session |
| `atelier/agent/AtelierSessionService.java` | Transmission de la configuration à l'ouverture |
| `resources/application.yml` | Valeurs par défaut |

---

## Plan de test

### Tests unitaires

- [ ] La création de session porte `model.id` et `model.effort.type`
- [ ] Un effort inconnu retombe sur `xhigh`, sans exception
- [ ] Le modèle configuré est bien celui envoyé (et non celui de l'agent provisionné)
- [ ] Les surcharges existantes cohabitent : prompt, outils `always_ask`, MCP, délégation **et** modèle dans le même objet
- [ ] Non-régression : les scénarios sans surcharge fonctionnelle envoient quand même le modèle

### Tests d'intégration

Sans objet : aucun endpoint, aucune migration.

### Isolation utilisateur

- [ ] **Non applicable** — configuration de plateforme, identique pour tous ; aucun accès aux données.

---

## Préoccupations transversales

| Préoccupation | Concernée | Analyse |
|--------------|-----------|---------|
| Auth / Principal | **Non** | Aucun changement. |
| Contexte tenant | **Non** | Configuration globale, aucun accès aux données. |
| Plans / limites | **Oui** | Le modèle et l'effort déterminent le **coût réel** d'un run. Composants vérifiés : le décompte au coût réel (F-36 / SF-36-02) lit `list_cost` du fournisseur — il suit donc le modèle servi **sans modification** ; le plafond de session (SF-36-01) est en dollars, donc indépendant du modèle ; les tarifs du rapport d'usage (SF-36-03) sont ceux d'Opus, inchangés puisqu'on reste sur Opus. Aucun quota à ajuster. |
| Navigation / routing | **Non** | Aucune route. |

---

## Dépendances

- Aucune. Cohabite avec F-31, F-33, F-34, F-35 dans la même surcharge de session.

---

## Notes et décisions

- **Surcharger par session plutôt que re-provisionner l'agent** : l'agent est un objet versionné chez
  le fournisseur, partagé par toutes les sessions. Le mettre à jour à chaque changement de
  configuration demanderait une réconciliation et un versionnement ; la surcharge de session obtient le
  même résultat, immédiatement, et réversible en une variable d'environnement.
- **Opus 5 plutôt qu'Opus 4.8** : même tarif, modèle plus récent. Ne pas en profiter serait payer le
  même prix pour moins.
- **`xhigh` conservé par défaut** : c'est le réglage effectif d'aujourd'hui, et celui que la
  documentation recommande pour le travail agentique de code. Le baisser est désormais possible — mais
  cela se décide **sur les mesures** que F-36 produit, pas sur une intuition.
