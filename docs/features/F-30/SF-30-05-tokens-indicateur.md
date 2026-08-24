# Mini-spec — [F-30 / SF-05] Tokens dans l'indicateur d'activité

---

## Identifiant

`F-30 / SF-05`

## Feature parente

`F-30` — Atelier — expérience terminal

## Statut

`ready`

## Date de création

2026-08-24

## Branche Git

`feat/SF-30-05-tokens-indicateur`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Afficher **ce qu'a coûté le tour** — tokens consommés et durée — une fois l'exécution terminée,
complétant l'indicateur d'activité livré en SF-30-02.

---

## Contexte

ADR-014 décrit un indicateur d'activité portant « la durée écoulée **et les tokens consommés** ».
SF-30-02 n'a livré que la durée, pour une raison précise : le flux SSE ne transporte aucune donnée de
consommation. Celle-ci est relevée **après** le run (`getSessionUsage`, SF-28-12) et, depuis SF-30-04,
elle est déjà calculée **en delta** par tour — mais elle reste confinée au décompte de quota, sans
jamais remonter jusqu'à l'écran.

L'écart est donc à combler en faisant remonter ce delta déjà calculé, pas en instrumentant quoi que
ce soit de neuf.

---

## Comportement attendu

### Cas nominal

1. À la fin d'un tour en mode Terminal, le backend joint au résultat les **tokens du tour**
   (entrée + sortie) et les **secondes de sandbox** — les mêmes deltas que ceux décomptés du quota.
2. L'événement SSE `done` les transporte, en **champs additifs**.
3. Le tour assistant affiche « `m:ss` · N tokens » sous la transcription.
4. Les nombres sont formatés lisiblement (séparateur de milliers, locale française).

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Relevé d'usage en échec (best-effort) | Aucun chiffre affiché — **pas de « 0 token »**, qui serait un mensonge sur une exécution réelle |
| Consommation nulle relevée | Rien n'est affiché non plus (indiscernable d'un relevé manqué, et sans intérêt) |
| Client ignorant les nouveaux champs | Fonctionne exactement comme avant (champs strictement additifs) |
| Mode Assistant | Inchangé : aucun indicateur de ce type (pas de session sandbox) |

---

## Critères d'acceptation

- [ ] `AtelierSessionResult` porte les tokens (entrée, sortie) et les secondes du **tour**, pas le cumul de session
- [ ] Ce sont **exactement** les deltas décomptés du quota (une seule source, pas un second calcul)
- [ ] Un relevé d'usage en échec laisse ces valeurs à **zéro** et le run aboutit normalement (best-effort inchangé)
- [ ] L'événement SSE `done` porte les nouveaux champs ; sa forme reste rétrocompatible
- [ ] Les événements SSE existants sont inchangés
- [ ] Le tour assistant affiche durée + tokens ; **rien** n'est affiché si la consommation est inconnue ou nulle
- [ ] Le mode Assistant est inchangé
- [ ] Aucun endpoint créé, aucune table, aucune migration

---

## Périmètre

### Hors scope

- Coût en euros : la conversion tokens→prix relève de la facturation (F-21), pas de l'écran d'exécution
- Consommation **en direct** pendant le run : l'API n'expose l'usage qu'en interrogeant la session ;
  la sonder en boucle ajouterait des appels réseau pour un gain d'affichage marginal
- Historique de consommation par workspace : relève du suivi de quota, écran distinct

---

## Contraintes de validation

| Élément | Contrainte |
|---------|-----------|
| Tokens affichés | Somme entrée + sortie du tour, séparateur de milliers (`fr-FR`) |
| Durée affichée | Reprend le format `m:ss` de SF-30-02 |
| Valeur inconnue / nulle | Aucun affichage (jamais « 0 token ») |
| Source | Les deltas déjà calculés par `recordSessionUsage` — pas de second calcul |

---

## Technique

### Endpoint(s)

Aucun créé. L'événement `done` de `POST /workspaces/{id}/agent/stream` gagne des champs additifs.

### Tables impactées / Migration

Aucune.

### Fichiers impactés

| Fichier | Nature |
|---------|--------|
| `atelier/agent/AtelierSessionResult.java` | + tokens et secondes du tour |
| `atelier/agent/AtelierSessionService.java` | `recordSessionUsage` renvoie les deltas au lieu de les avaler |
| `atelier/AtelierAgentController.java` | `StreamDone` enrichi |
| `core/models/atelier.models.ts` | `AtelierAgentStreamDone` enrichi |
| `core/services/atelier.service.ts` | Lecture des nouveaux champs |
| `atelier/atelier.component.{ts,html,scss}` | Affichage « durée · tokens » sur le tour terminé |

---

## Plan de test

### Tests unitaires

- [ ] Backend : le résultat porte les deltas du tour (second tour d'une session : l'écart, pas le cumul)
- [ ] Backend : relevé d'usage en échec → valeurs à zéro, run abouti (best-effort inchangé)
- [ ] Backend : l'événement `done` transporte les nouveaux champs
- [ ] Frontend : `done` avec tokens → « durée · tokens » affichés sur le tour
- [ ] Frontend : `done` sans tokens (zéro) → **rien** d'affiché
- [ ] Frontend : non-régression du `done` existant (réponse + fichiers modifiés)

### Tests d'intégration

Sans objet : aucun endpoint créé ; le flux est couvert par les tests du contrôleur.

### Isolation utilisateur

- [ ] **Non applicable** — aucun accès aux données ajouté. La consommation affichée est celle du run
  en cours, déjà borné par `requireOwned`, et n'est jamais lue depuis un autre workspace.

---

## Préoccupations transversales

| Préoccupation | Concernée | Analyse |
|--------------|-----------|---------|
| Auth / Principal | **Non** | Aucun changement. |
| Contexte tenant | **Non** | Aucun nouveau chemin d'accès aux données. |
| Plans / limites | **Oui** | On **expose** ce qui est déjà décompté, sans changer le décompte. Composants vérifiés : `recordSessionUsage` (change de type de retour, la logique de delta est identique), `QuotaService.recordUsage` / `recordSandboxSeconds` (appels et valeurs inchangés), pré-vol `assertWithinQuota` / `assertWithinSandboxLimit` (intouchés). Aucun autre appelant modifié. |
| Navigation / routing | **Non** | Aucune route. |

---

## Dépendances

- **SF-30-02 (Done)** — indicateur d'activité et transcription.
- **SF-30-04 (Done)** — calcul du delta d'usage par tour.

---

## Notes et décisions

- **Ne rien afficher plutôt qu'afficher zéro** : le relevé d'usage est best-effort. Un « 0 token »
  après une exécution réelle serait faux ; l'absence de chiffre est honnête.
- **Une seule source de vérité** : les valeurs affichées sont exactement celles créditées au quota.
  Recalculer côté affichage ouvrirait la porte à un écart entre ce que l'utilisateur voit et ce qui
  lui est facturé.
