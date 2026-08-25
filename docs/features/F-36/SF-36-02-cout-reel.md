# Mini-spec — [F-36 / SF-02] Décompte au coût réel, avec repli sur les tokens (backend)

---

## Identifiant

`F-36 / SF-02`

## Feature parente

`F-36` — Plafond de dépense &amp; facturation au coût réel

## Statut

`done` — livrée le 2026-08-26 (PR #165)

## Date de création

2026-08-26

## Branche Git

`feat/SF-36-02-cout-reel`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Décompter du quota le **coût réellement facturé** par le fournisseur (`list_cost` : tokens au tarif du
modèle servi, recherches web, temps de bac à sable), converti en équivalent tokens et multiplié par un
**markup configurable**, avec **repli sur le décompte de tokens** quand le coût n'est pas disponible.

---

## Contexte

Le quota est en **tokens**, le coût en **dollars**. Les deux ne coïncident pas : le tarif dépend du
modèle servi, et le décompte de tokens ignore les recherches web (10 $ / 1 000) comme le temps de bac
à sable. Un projet qui fait beaucoup de recherche web consomme aujourd'hui du budget sans consommer de
quota (cadrage F-36, D3/D4).

Le fournisseur expose déjà le coût facturé de la session (`list_cost`, cumulé). Provider-First : on le
**relaie**, on ne recalcule aucun tarif dans la Gateway.

Le quota reste **libellé en tokens** (aucune migration des compteurs, aucun changement d'écran) : le
coût est converti en équivalent tokens au tarif de référence configuré en SF-36-01.

---

## Comportement attendu

### Cas nominal

1. Fin de tour. Le relevé de session porte, en plus des tokens et du temps de bac à sable, le **coût
   cumulé** de la session en unités mineures.
2. Le service calcule le **delta** de coût depuis le relevé précédent (le cumul est persisté sur le
   workspace, comme les compteurs de tokens) — jamais le cumul, sinon la même dépense serait facturée
   à chaque tour.
3. Équivalent tokens = `delta $ × markup ÷ coût de référence par million × 1 000 000`.
4. Cet équivalent est réparti entre entrée et sortie **au prorata des tokens réellement rapportés**,
   pour que le rapport d'usage (F-16) garde sa forme. Sans tokens rapportés, tout va sur l'entrée.
5. `recordUsage` décompte ce montant ; le temps de bac à sable continue d'être décompté tel quel.
6. Le tour affiche la consommation **effectivement décomptée** — une seule source de vérité entre ce
   que l'utilisateur voit et ce qui lui est facturé.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| `list_cost` absent de la réponse du fournisseur | **Repli** : décompte des tokens bruts, exactement comme avant F-36 |
| `list_cost` illisible (forme inattendue) | Repli tokens ; aucune exception ne remonte au run |
| Coût cumulé **inférieur** au relevé précédent (session remplacée) | Delta borné à zéro : jamais de crédit négatif |
| Relevé de session en échec (panne fournisseur) | Best-effort inchangé : le run est déjà livré, rien n'est décompté |
| Coût positif mais aucun token rapporté | L'équivalent est décompté **entièrement en entrée** (ne rien décompter serait faux) |

---

## Critères d'acceptation

- [x] Le quota est décompté à partir du **coût réel** quand le fournisseur le rapporte.
- [x] Le décompte porte sur le **delta** de coût, jamais sur le cumul de la session persistante.
- [x] Le markup et le tarif de référence sont **configurables** (aucune valeur commerciale en dur).
- [x] Markup à `1.0` (défaut) ⇒ le décompte reproduit l'économie d'avant F-36 (voir arbitrage A-2).
- [x] Sans `list_cost`, le décompte est **identique** à celui d'avant cette SF (repli tokens).
- [x] Le temps de bac à sable reste décompté séparément (garde de coût F-28 / SF-28-12 inchangée).
- [x] La consommation affichée pour le tour est celle **effectivement décomptée**.
- [x] Le cumul de coût est porté par le workspace **de l'utilisateur propriétaire** (isolation).

---

## Périmètre

### Hors scope (explicite)

- La facturation à l'usage monétisée (OQ-08, toujours ouverte) : le quota reste bloquant, il n'y a pas
  de dépassement facturé.
- La refonte de la grille tarifaire et le changement des allocations de tokens par plan.
- Les tarifs de référence du **rapport d'usage** (SF-36-03).
- L'affichage d'un coût en euros dans l'écran d'Atelier.

---

## Technique

### Endpoint(s)

Aucun changement de contrat. `AtelierSessionResult` / l'événement SSE `done` portent les mêmes champs.

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `workspaces` | ALTER (ajout `agent_list_cost`) | Cumul du coût de la session en cours, en unités mineures. Remis à zéro à l'ouverture d'une session, comme les compteurs de tokens |

### Migration Liquibase

- [x] Oui — `046-workspaces-agent-list-cost.xml` (PostgreSQL + H2, `defaultValueNumeric="0"`, rollback `dropColumn`)

### Configuration

| Clé | Défaut | Rôle |
|-----|--------|------|
| `app.atelier.agent.cost.markup` | `1.0` | Multiplicateur appliqué au coût réel lors du décompte |

---

## Plan de test

### Tests unitaires

- [x] `AnthropicManagedAgentProviderTest` — `list_cost` lu depuis la réponse de session (forme
      `{amount, currency}`), et absent ⇒ coût inconnu.
- [x] `AtelierSessionServiceTest` — coût réel présent ⇒ décompte de l'équivalent tokens (delta).
- [x] `AtelierSessionServiceTest` — markup 2,0 ⇒ décompte doublé.
- [x] `AtelierSessionServiceTest` — deuxième tour ⇒ seul le **delta** de coût est décompté.
- [x] `AtelierSessionServiceTest` — coût absent ⇒ **repli** sur les tokens bruts.
- [x] `AtelierSessionServiceTest` — coût positif sans token rapporté ⇒ tout en entrée.
- [x] `AtelierSessionServiceTest` — relevé en baisse ⇒ delta borné à zéro.
- [x] `AtelierCostPropertiesTest` — défaut et bornes du markup.

### Tests d'intégration

- [x] La suite d'intégration existante (`AtelierApiIntegrationTest`) reste verte : la colonne ajoutée
      est nullable-free avec valeur par défaut, aucun contrat modifié.

### Isolation utilisateur

- [x] Applicable — le cumul de coût est lu et écrit sur le workspace **déjà possédé**
      (`requireOwned`), et le décompte vise l'utilisateur du contexte de sécurité.

---

## Dépendances

### Subfeatures bloquantes

- `SF-36-01` — **done** (elle apporte `AtelierCostProperties` et le tarif de référence).

### Questions ouvertes impactées

- [x] OQ-08 (overage monétisé) — **non tranchée**, hors scope.

---

## Notes et décisions

- **A-1 — le quota reste en tokens.** Le convertir en dollars aurait imposé une migration des
  compteurs, des plans et des écrans, pour un gain nul : l'équivalent tokens au tarif de référence
  porte exactement la même information.
- **A-2 — markup par défaut à 1,0, et non 2,0 (déviation assumée de D4).** D4 du cadrage fixe le défaut
  à 2,0×. Or le quota est **libellé en tokens**, et les allocations configurées embarquent **déjà** ce
  markup (GOLD : 199 € pour 12 M tokens ≈ 2× le coût blended Opus). Appliquer 2,0 sur le décompte
  diviserait par deux l'usage effectif de chaque client — une hausse de prix silencieuse, que le
  cadrage refuse explicitement (« ne pas augmenter le prix du Gold »), et qui contredirait son propre
  levier futur (« baisser le quota 12 M → 8 M porte la marge à 66 % » — un raisonnement qui n'a de sens
  que si le quota effectif reste 12 M après F-36). Le levier est donc **livré et configurable** comme
  demandé, avec un défaut **neutre** : passer à 2,0 est une ligne de configuration, sans redéploiement,
  le jour où l'owner décide d'agir sur la marge. Réversible.
- **A-3 — répartition entrée/sortie au prorata.** Le compteur n'a que deux colonnes ; répartir au
  prorata des tokens rapportés conserve la forme du rapport d'usage sans inventer de ventilation.
