# Mini-spec — [F-36 / SF-03] Tarifs de référence du rapport d'usage corrigés (backend, configuration)

---

## Identifiant

`F-36 / SF-03`

## Feature parente

`F-36` — Plafond de dépense &amp; facturation au coût réel

## Statut

`in-progress`

## Date de création

2026-08-26

## Branche Git

`feat/SF-36-03-tarifs-rapport`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Corriger les tarifs de référence du rapport d'usage &amp; coût (F-16), qui estimaient la dépense aux
tarifs **Sonnet** (3 / 15 par million) alors que l'Atelier tourne sur **Opus** (5 / 25) — une
sous-estimation d'environ **40 %**.

---

## Contexte

Le rapport d'usage applique un tarif « blended » configuré à l'ensemble de la période (les compteurs
F-10 n'ont pas de ventilation par modèle). Ce tarif a été posé en F-16 aux valeurs de Sonnet. Depuis
F-28, l'Atelier — l'usage le plus vorace — tourne sur Opus, dont le tarif public est **5 / 25** par
million de tokens. Le rapport décrivait donc un modèle que la plateforme n'utilise pas (cadrage F-36,
D5).

Aucune logique ne change : seules les **valeurs par défaut** de la configuration sont corrigées, et
elles restent surchargeables par environnement.

---

## Comportement attendu

### Cas nominal

1. `GET /usage/report` renvoie, pour chaque période, un coût estimé calculé au tarif configuré.
2. Les défauts passent de `3.00` / `15.00` à **`5.00` / `25.00`** par million de tokens.
3. Un environnement qui surcharge `APP_USAGE_INPUT_COST_PER_M` / `APP_USAGE_OUTPUT_COST_PER_M` garde
   ses valeurs : rien n'est imposé en dur.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Valeur configurée absente | Défaut appliqué (désormais 5,00 / 25,00) — comportement inchangé |
| Valeur configurée négative | Défaut appliqué (garde existante) |
| Aucune période enregistrée | Rapport vide, coût total à zéro (inchangé) |

---

## Critères d'acceptation

- [ ] Le tarif d'entrée par défaut vaut **5,00** par million ; celui de sortie **25,00**.
- [ ] Le coût estimé d'une période connue est celui calculé aux nouveaux tarifs.
- [ ] Une valeur explicitement configurée reste prioritaire sur le défaut.
- [ ] Aucun changement de contrat d'API ni de forme de réponse.
- [ ] L'isolation `user_id` du rapport reste inchangée (test existant vert).

---

## Périmètre

### Hors scope (explicite)

- Le changement de la **devise** d'affichage (`app.usage.report.currency`, défaut `EUR`) : la
  conversion de devise n'est pas l'objet de cette SF (voir Notes, A-1).
- La ventilation par modèle des compteurs d'usage (les compteurs F-10 n'en portent pas).
- Le décompte du quota lui-même (SF-36-02).

---

## Technique

### Endpoint(s)

`GET /api/v1/usage/report` — contrat **inchangé**, seules les valeurs changent.

### Tables impactées

Aucune.

### Migration Liquibase

- [x] Non applicable

### Configuration

| Clé | Avant | Après |
|-----|-------|-------|
| `app.usage.report.input-cost-per-million-tokens` | `3.00` | `5.00` |
| `app.usage.report.output-cost-per-million-tokens` | `15.00` | `25.00` |

---

## Plan de test

### Tests unitaires

- [ ] `UsageReportPropertiesTest` — les défauts valent 5,00 et 25,00 ; une valeur configurée est
      conservée ; une valeur négative retombe sur le défaut.
- [ ] `UsageReportServiceTest` — le coût d'une période est calculé aux tarifs Opus.

### Tests d'intégration

- [ ] `UsageReportApiIntegrationTest` — le rapport reste servi, isolation par utilisateur inchangée.

### Isolation utilisateur

- [x] Applicable — couverte par les tests existants du rapport (aucun changement d'accès aux données).

---

## Dépendances

### Subfeatures bloquantes

- Aucune (indépendante de SF-36-01 / SF-36-02).

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **A-1 — devise inchangée.** Les tarifs publics du fournisseur sont en dollars, le rapport affiche
  `EUR` par défaut. Convertir supposerait un taux de change entretenu quelque part, pour un rapport
  qui s'annonce déjà comme une **estimation**. Le défaut reste donc `EUR` avec les valeurs en tarif
  public : l'estimation devient légèrement **conservatrice** (elle surestime d'environ le change),
  ce qui est le bon sens de l'erreur pour un indicateur de coût. Réversible par configuration.
