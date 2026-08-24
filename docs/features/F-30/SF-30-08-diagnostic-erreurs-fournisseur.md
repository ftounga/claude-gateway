# Mini-spec — [F-30 / SF-08] Diagnostic des erreurs du fournisseur d'agents

---

## Identifiant

`F-30 / SF-08`

## Feature parente

`F-30` — Atelier — expérience terminal

## Statut

`ready`

## Date de création

2026-08-24

## Branche Git

`feat/SF-30-08-diagnostic-erreurs-fournisseur`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Rendre diagnosticable en une lecture de log un échec d'appel au fournisseur d'agents, et distinguer
côté utilisateur un **crédit épuisé** d'une panne d'exécution.

---

## Contexte

Le 2026-08-24, une exécution a échoué en production. Le log disait seulement :

```
WARN  Appel au fournisseur d'agents en échec (création de la session)
```

Ni code HTTP, ni type d'erreur. Il a fallu interroger l'API à la main pour découvrir une cause
triviale : `HTTP 400 — invalid_request_error — "Your credit balance is too low"`. L'utilisateur, lui,
voyait « L'exécution a échoué. Veuillez réessayer. » — un message qui invite à refaire exactement ce
qui ne peut pas marcher.

La discrétion du log est **volontaire** (ne jamais exposer la clé ni la réponse brute) et cette règle
est conservée : on ajoute le **code HTTP** et le **type d'erreur**, pas le corps.

---

## Comportement attendu

### Cas nominal

1. Tout échec d'appel au fournisseur logge l'opération, le **statut HTTP** et le **type d'erreur**
   renvoyé (`invalid_request_error`, `authentication_error`, `rate_limit_error`, …).
2. Ni la clé d'API, ni le corps brut de la réponse, ni aucune donnée utilisateur n'apparaissent.
3. Un solde de crédits épuisé est reconnu et produit une exception dédiée.
4. Le flux SSE relaie alors le code `credit_exhausted`, et l'écran affiche un message qui dit quoi
   faire au lieu d'inviter à réessayer.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Réponse d'erreur sans corps JSON exploitable | Statut loggé, type « inconnu » ; aucune exception supplémentaire |
| Message du fournisseur reformulé à l'avenir | La détection retombe sur l'erreur générique — **dégradation propre**, jamais d'échec |
| Erreur réseau (pas de réponse HTTP) | Loggé comme tel, sans statut ; comportement d'appel inchangé |
| Crédit épuisé hors création de session (message, événements…) | Même reconnaissance : la détection est faite au niveau de l'appel HTTP, pas d'un endpoint |

---

## Critères d'acceptation

- [ ] Chaque échec d'appel au fournisseur logge : opération, statut HTTP, type d'erreur
- [ ] **Aucune** clé d'API, réponse brute ou donnée utilisateur dans les logs
- [ ] Un solde épuisé lève `AgentCreditExhaustedException` (sous-type d'erreur fournisseur)
- [ ] La détection est **défensive** : un message reformulé retombe sur l'erreur générique
- [ ] Le flux SSE émet `credit_exhausted` ; les autres codes d'erreur sont inchangés
- [ ] L'écran affiche un message distinct, qui n'invite pas à réessayer
- [ ] Le comportement d'appel (retours, exceptions existantes) est inchangé pour tous les autres cas
- [ ] Aucun endpoint, aucune table, aucune migration

---

## Périmètre

### Hors scope

- **Le même diagnostic sur le fournisseur de chat** (`AIProvider`, F-02) : le besoin est identique et
  la panne l'a touché aussi, mais c'est un autre composant — subfeature distincte à planifier.
- Alerte / supervision sur crédit bas : relève du monitoring (C3), pas de l'exécution
- Exposition du solde dans l'interface : nécessiterait un appel de facturation Anthropic, hors sujet

---

## Contraintes de validation

| Élément | Contrainte |
|---------|-----------|
| Contenu du log | Opération + statut + type d'erreur **uniquement** |
| Détection crédit | Sur le type `invalid_request_error` **et** un message évoquant le solde ; sinon générique |
| Code SSE | `credit_exhausted` |
| Message utilisateur | Indique que le service est indisponible pour cause de crédit, sans inviter à réessayer |

---

## Technique

### Endpoint(s)

Aucun. Le flux `POST /workspaces/{id}/agent/stream` gagne une valeur possible du champ `error`.

### Tables impactées / Migration

Aucune.

### Fichiers impactés

| Fichier | Nature |
|---------|--------|
| `atelier/agent/AnthropicManagedAgentProvider.java` | Log enrichi + détection du crédit épuisé |
| `atelier/agent/AgentCreditExhaustedException.java` | **Nouveau** |
| `atelier/AtelierAgentController.java` | Traduction en code SSE `credit_exhausted` |
| `core/services/atelier.service.ts` | — (le code transite déjà) |
| `atelier/atelier.component.ts` | Message utilisateur dédié |

---

## Plan de test

### Tests unitaires

- [ ] Réponse 400 « credit balance too low » → `AgentCreditExhaustedException`
- [ ] Réponse 400 d'un autre type → exception générique inchangée
- [ ] Réponse 401/429/500 → exception générique, aucune régression
- [ ] Réponse d'erreur sans JSON exploitable → exception générique, aucune exception de parsing
- [ ] Contrôleur : `AgentCreditExhaustedException` → événement SSE `credit_exhausted`
- [ ] Contrôleur : les autres erreurs conservent leurs codes actuels (non-régression)
- [ ] Frontend : `credit_exhausted` → message dédié ; codes existants inchangés

### Tests d'intégration

Sans objet : aucun endpoint créé ; le flux est couvert par les tests du contrôleur.

### Isolation utilisateur

- [ ] **Non applicable** — aucun accès aux données ; le run reste borné par `requireOwned`.

---

## Préoccupations transversales

| Préoccupation | Concernée | Analyse |
|--------------|-----------|---------|
| Auth / Principal | **Non** | Aucun changement. |
| Contexte tenant | **Non** | Aucun accès aux données ajouté. |
| Plans / limites | **Non** | Le crédit **fournisseur** (compte Anthropic) n'est pas le quota utilisateur : aucun appel à `QuotaService` n'est ajouté ni modifié. Les deux notions restent distinctes — un quota utilisateur épuisé produit toujours `quota_exceeded`. |
| Navigation / routing | **Non** | Aucune route. |

---

## Dépendances

- Aucune.

---

## Notes et décisions

- **Ne pas inviter à réessayer une action qui ne peut pas aboutir** : c'est le vrai défaut corrigé
  ici. Un message générique a coûté un diagnostic manuel et aurait pu coûter des tentatives inutiles.
- **Détection par message, assumée comme heuristique** : l'API ne fournit pas de code d'erreur dédié
  au solde. Si le libellé change, on retombe sur l'erreur générique — la dégradation est prévue,
  testée, et ne casse rien.
- **La discrétion du log est conservée** : statut et type suffisent à orienter, sans jamais exposer
  la clé ni la réponse brute.
