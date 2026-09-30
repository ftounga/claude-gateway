# Mini-spec — [F-39 / SF-39-23] Relever le plafond de consommation par message

> Décision PO du 2026-09-30 : relever drastiquement le plafond de consommation d'un
> message de la boucle d'agent de l'Atelier (le message « Ce message a atteint son
> plafond de consommation… », `budgetReached`), aligné sur la règle absolue « justesse
> avant coût » (aucune réduction de la marge de raisonnement).

---

## Identifiant

`F-39 / SF-39-23`

## Feature parente

`F-39` — L'Atelier comme harnais

## Statut

`ready`

## Date de création

2026-09-30

## Branche Git

`feat/SF-39-23-relever-plafond-consommation-message`

---

## Objectif

Relever le plafond de consommation par message de la boucle maison (config 8 M → 30 M,
borne dure du code 10 M → 40 M) et fixer explicitement le plafond d'itérations à 60,
pour qu'un même message aille beaucoup plus loin avant de rendre la main, sans réduire
aucune marge de raisonnement.

---

## Comportement attendu

### Cas nominal

Un message long de la boucle d'agent (construction lourde, config complète) poursuit son
travail jusqu'à **30 M** tokens traités (cache compris, comme le compteur de quota) au
lieu de 8 M, et jusqu'à **60** allers-retours au lieu de 30, avant que `budgetReached`
(plafond `maxTurnTokens`) ou le plafond d'itérations ne l'arrête. Le budget de **temps**
du tour (`APP_ATELIER_TURN_BUDGET=PT60M`) reste inchangé et borne toujours la durée. Le
plafond additionne les tokens **traités** cache compris (SF-39-01, D3) : les relectures
étant servies par le cache au ~1/10 du tarif, relever le plafond ne multiplie pas la
facture dans les mêmes proportions. Le **quota mensuel** reste la borne qui mesure ce que
l'utilisateur a payé — inchangé.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| `APP_ATELIER_MAX_TURN_TOKENS` absent / nul / négatif | Repli sur `DEFAULT_MAX_TURN_TOKENS` (4 M) — inchangé |
| `APP_ATELIER_MAX_TURN_TOKENS` au-delà de la borne dure (ex. 50 M) | Ramené à `MAX_TURN_TOKENS_CEILING` (désormais 40 M) |
| `APP_ATELIER_MAX_ITERATIONS` absent / nul / négatif | Repli sur le défaut (30) — inchangé |
| `APP_ATELIER_MAX_ITERATIONS` au-delà de 100 | Ramené à 100 — inchangé |

---

## Critères d'acceptation

- [ ] `MAX_TURN_TOKENS_CEILING` vaut **40 000 000** (au lieu de 10 000 000) ; son Javadoc
      reflète la décision PO datée 2026-09-30.
- [ ] `DEFAULT_MAX_TURN_TOKENS` **reste 4 000 000** (repli inchangé).
- [ ] Une valeur configurée de **30 M** est acceptée telle quelle (sous la nouvelle borne).
- [ ] Une valeur configurée de **50 M** est ramenée à **40 M** (nouvelle borne dure).
- [ ] Une valeur configurée d'itérations de **60** est acceptée telle quelle.
- [ ] Valeurs absentes → défauts (4 M pour les tokens, 30 pour les itérations).
- [ ] `configmap.yaml` : `APP_ATELIER_MAX_TURN_TOKENS = 30000000`,
      `APP_ATELIER_MAX_ITERATIONS = 60` (nom d'env vérifié), `APP_ATELIER_TURN_BUDGET`
      inchangé (`PT60M`), commentaires datés au-dessus de chaque valeur changée.

---

## Périmètre

### Hors scope (explicite)

- Ne touche PAS `DEFAULT_MAX_TURN_TOKENS` (le repli reste 4 M).
- Ne touche PAS `turnBudget` / `APP_ATELIER_TURN_BUDGET` (reste `PT60M`).
- Ne touche PAS le modèle, l'effort, ni aucun autre réglage de l'Atelier.
- Ne réduit AUCUNE marge de raisonnement (règle absolue PO « justesse avant coût »).
- Aucun endpoint, aucune table, aucune migration, aucun composant frontend.
- Ne déploie pas : le configmap sera appliqué par le déploiement unique de fin de vague.

---

## Technique

### Endpoint(s)

Aucun.

### Tables impactées

Aucune. Aucune migration Liquibase.

### Fichiers impactés

| Fichier | Opération | Notes |
|---------|-----------|-------|
| `backend/src/main/java/fr/claudegateway/atelier/AtelierProperties.java` | MODIF | `MAX_TURN_TOKENS_CEILING` 10 M → 40 M + Javadoc daté |
| `backend/src/test/java/fr/claudegateway/atelier/AtelierPropertiesTest.java` | MODIF | test pinnant la nouvelle borne (30 M accepté, 50 M → 40 M) |
| `k8s/base/backend/configmap.yaml` | MODIF | `APP_ATELIER_MAX_TURN_TOKENS` 8 M → 30 M ; ajout `APP_ATELIER_MAX_ITERATIONS: 60` |

### Nom d'environnement (vérifié)

`app.atelier.max-iterations` (binding Spring) ↔ `APP_ATELIER_MAX_ITERATIONS`.
Preuve : `application.yml` → `max-iterations: ${APP_ATELIER_MAX_ITERATIONS:30}`.

---

## Plan de test

### Tests unitaires (`AtelierPropertiesTest`)

- [ ] `maxTurnTokens` = 30 M configuré → conservé (sous la nouvelle borne 40 M).
- [ ] `maxTurnTokens` = 50 M configuré → ramené à `MAX_TURN_TOKENS_CEILING` (= 40 M).
- [ ] La constante `MAX_TURN_TOKENS_CEILING` vaut 40 M.
- [ ] `maxIterations` = 60 configuré → conservé (déjà couvert par `honoursAConfiguredValue`
      qui vérifie une valeur intermédiaire ; ajout de 60 pour tracer la décision).
- [ ] Valeurs absentes → défauts (4 M / 30) — tests existants inchangés.

### Tests d'intégration

Sans objet (pas d'endpoint).

### Isolation utilisateur

Non applicable — réglage global d'infrastructure, sans accès à des données par `user_id`.

---

## Dépendances

### Subfeatures bloquantes

- `SF-39-15` — plafond de dépense par message (`maxTurnTokens`) — **done**.
- `SF-28-19` — plafond d'itérations (`maxIterations`) — **done**.

### Questions ouvertes impactées

Aucune.

---

## Notes et décisions

- **D1** : borne dure relevée à **40 M** (et non exactement 30 M) pour laisser du
  *headroom* au-dessus de la config 30 M — on pourra régler plus haut sans nouvelle
  livraison, jusqu'à la borne.
- **D2** : plafond d'itérations posé à **60** en config. Une fois les tokens relevés, les
  itérations (défaut 30) deviendraient le nouveau goulot ; 60 les aligne sur la nouvelle
  marge. Toujours sous le plafond dur du code (100).
- **D3** : aligné sur la règle absolue PO du 2026-09-30 — ce changement **augmente** la
  marge de raisonnement, il ne réduit rien.
- **Préoccupations transversales** : aucune (pas d'auth, pas de contexte tenant, pas de
  plan/limite métier, pas de navigation/routing). Le seul « plafond » touché est un
  garde-fou d'infrastructure de la boucle, sans lien avec les quotas de facturation.
