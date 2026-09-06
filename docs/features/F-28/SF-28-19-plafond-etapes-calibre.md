# Mini-spec — F-28 / SF-28-19 — Un plafond d'étapes calibré sur l'usage réel

## Identifiant

`F-28 / SF-28-19`

## Feature parente

`F-28` — Atelier (Claude Code Lite)

## Statut

`ready`

## Date de création

2026-09-06

## Branche Git

`feat/SF-28-19-plafond-etapes-calibre`

---

## Objectif

> Porter le plafond d'allers-retours d'un message de **12 à 30**, et le rendre **configurable**, pour
> qu'un tiers des demandes réelles cesse d'être coupé en chemin — sans ouvrir la dépense, que le
> budget de temps et le quota bornent déjà.

---

## Déclencheur

Audit de l'**usage réel** (2026-09-06) sur les 6 sessions Claude Code de ce projet, 21 août →
5 septembre. Ce ne sont pas des estimations : ce sont les transcrits.

| Mesure | Valeur |
|---|---|
| Demandes réelles | 158 |
| Appels d'outils | 1 714, dont **1 630 `bash` (95 %)** |
| Outils par demande | médiane **6**, moyenne **13,8**, max **125** |
| Demandes > 12 outils | **31 %** |
| Demandes > 30 outils | 15 % |

Le plafond actuel de 12 couperait **31 % des demandes** de l'usage qu'on cherche précisément à
servir. C'est le seul chiffre de la boucle maison que l'usage réel franchit en permanence.

---

## Comportement attendu

### Cas nominal

Inchangé pour les 69 % de demandes qui tiennent en 12 étapes. Au-delà, la boucle continue jusqu'à
**30** étapes au lieu de s'arrêter, puis rend le même message de plafond qu'aujourd'hui.

### Bornes qui continuent de s'appliquer, inchangées

Le plafond d'étapes n'est pas la seule borne, et ce n'est pas la plus forte :

| Borne | Valeur | Effet |
|---|---|---|
| Budget de temps du tour | `TURN_BUDGET_MS` = 10 min | la boucle rend la main avant l'expiration du flux SSE |
| Quota de tokens (Hosted) | par utilisateur | contrôlé avant l'appel, décompté après |
| Autorisation de commande | à chaque `bash` en cible `RUNNER` | non désactivable |
| Interruption | à tout moment | `Ctrl-C` logique, propagé jusqu'à la commande |

Relever le plafond d'étapes **n'ouvre donc aucune dépense non bornée** : il déplace seulement le
moment où la boucle s'arrête d'elle-même, à l'intérieur de bornes déjà posées.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Plafond atteint | Message « limite d'étapes » (inchangé), tour clos proprement, travail déjà fait conservé | 200 |
| Budget de temps atteint avant le plafond | Message de budget (inchangé) — la borne la plus stricte gagne | 200 |
| `max-iterations` absent ou ≤ 0 en configuration | Retombe sur le défaut 30 | — |
| `max-iterations` déraisonnable (> 100) | Ramené à 100 : au-delà, le budget de temps aurait tranché de toute façon | — |

---

## Critères d'acceptation

- [ ] Une demande nécessitant 20 allers-retours aboutit au lieu d'être coupée à 12.
- [ ] Le plafond est lu dans la configuration ; défaut **30**.
- [ ] Une valeur nulle, négative ou absente retombe sur 30 ; une valeur > 100 est ramenée à 100.
- [ ] Le message rendu au plafond est inchangé.
- [ ] Le budget de temps reste prioritaire : atteint en premier, c'est son message qui est rendu.
- [ ] Isolation `user_id` inchangée.
- [ ] Aucune régression sur les tours courts (le cas de 69 % des demandes).

---

## Périmètre

### Hors scope (explicite)

- **R6 — cache de prompt.** C'est le vrai levier de rentabilité (§Notes, D2), et il mérite sa propre
  subfeature ; ce plafond est calibré pour rester tenable **sans** lui.
- **R4** — rejeu de la trajectoire d'outils. Un tour relancé après plafond repart toujours sans
  mémoire ; le message de plafond reste donc, à ce stade, une invitation imparfaite.
- R3, R5, R7, R8 — inchangés.
- Aucun écran, aucun endpoint.

---

## Valeurs initiales

Sans objet.

---

## Contraintes de validation

| Champ | Obligatoire | Bornes | Valeurs autorisées | Normalisation |
|-------|-------------|--------|--------------------|---------------|
| `app.atelier.max-iterations` | Non | 1 – 100 | entier | absent / ≤ 0 ⇒ 30 ; > 100 ⇒ 100 |

---

## Technique

### Endpoint(s)

Aucun.

### Tables impactées

Aucune.

### Migration Liquibase

- [x] **Non applicable.**

### Classes impactées

| Classe | Changement |
|--------|-----------|
| `atelier/AtelierProperties` | Nouvelle propriété `maxIterations` (défaut 30, borne 100) |
| `atelier/AtelierChatService` | La constante `MAX_ITERATIONS` devient la propriété |
| `application.yml` | `max-iterations: ${APP_ATELIER_MAX_ITERATIONS:30}` |

### Composants Angular

Aucun.

---

## Préoccupations transversales

| Préoccupation | Concernée | Composants impactés |
|--------------|-----------|--------------------|
| Auth / Principal | Non | — |
| Contexte tenant | Non | `requireOwned` inchangé, premier geste |
| Plans / limites | **Oui** | Le quota (`QuotaService.assertWithinQuota` avant, `recordUsage` après) et le budget de session (F-36) sont inchangés et restent les bornes de dépense. Le plafond d'étapes n'est pas un gate de facturation : aucun appel aux services de limites n'est ajouté, retiré ni déplacé. Vérifié sur les deux chemins (Hosted et BYOK). |
| Navigation / routing | Non | — |

---

## Plan de test

### Tests unitaires

- [ ] `AtelierPropertiesTest` — défaut 30 ; valeur nulle, 0, négative ⇒ 30 ; 150 ⇒ 100 ; 20 ⇒ 20.
- [ ] `AtelierChatServiceTest` — une conversation de 20 appels d'outils aboutit (elle échouait à 12).
- [ ] `AtelierChatServiceTest` — au plafond configuré, le message de limite est rendu et le tour est
      clos ; les actions déjà réalisées sont conservées.
- [ ] `AtelierChatServiceTest` — non-régression : les tours courts sont inchangés.

### Tests d'intégration

- [ ] Couvert par les tests existants de `AtelierChatApiIntegrationTest` (le contrat de l'endpoint
      ne change pas) ; aucun nouveau test d'endpoint n'est justifié.

### Isolation workspace

- [x] Applicable — les tests d'isolation existants restent verts ; aucun nouveau chemin d'accès.

---

## Dépendances

### Subfeatures bloquantes

- `SF-28-18` — statut : done (2026-09-06)

### Questions ouvertes impactées

- [ ] Aucune.

---

## Notes et décisions

**D1 — 30 et non 40.** Le gain marginal se paie mal. Passer de 12 à 30 fait tomber le taux de
demandes coupées de 31 % à 15 % ; passer de 30 à 40 ne gagne que quelques points, alors que le coût
d'un tour croît **quadratiquement** avec le nombre d'itérations tant que le cache de prompt n'est pas
en place (D2). 30 est le point où la courbe d'utilité croise celle du coût.

**D2 — Pourquoi le coût croît quadratiquement, et pourquoi ça borne ce choix.** Sans
`cache_control`, chaque itération renvoie *tout* : consigne système (jusqu'à 40 000 caractères),
historique, définitions d'outils, plus les résultats d'outils accumulés. Le volume d'entrée facturé
sur un tour de N itérations est donc en N². Le cache de prompt (R6) ferait tomber la relecture du
préfixe stable à une fraction de son prix et changerait complètement l'arbitrage — **c'est lui, et
non le plafond, le levier de rentabilité**. Tant qu'il n'est pas livré, ce plafond reste le
garde-fou de dépense le plus lisible, d'où le choix de le rendre **configurable** : il se baisse sans
livraison.

**D3 — Le plafond n'est pas la borne de sécurité.** Le budget de temps de 10 minutes et le quota le
sont, et ils sont antérieurs. Le plafond d'étapes protège d'une boucle qui tournerait en rond, pas
d'une dépense qui filerait — cette dernière est déjà tenue ailleurs.
