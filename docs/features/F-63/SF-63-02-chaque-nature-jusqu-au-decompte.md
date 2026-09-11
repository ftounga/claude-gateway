# Mini-spec — F-63 / SF-63-02 — Chaque nature de token jusqu'au décompte

---

## Identifiant

`F-63 / SF-63-02`

## Feature parente

`F-63` — Le quota compte au coût réel (`docs/PRODUCT_SPEC.md`)

## Statut

`ready`

## Date de création

2026-09-11

## Branche Git

`feat/SF-63-02-cache-jusqu-au-decompte`

---

## Objectif

Faire voyager les tokens de **cache** — lus et écrits — séparément des tokens d'entrée, depuis la
réponse du fournisseur jusqu'au décompte, pour que SF-63-01 puisse les facturer au dixième et au
1,25× du tarif d'entrée au lieu du plein tarif.

---

## Comportement attendu

### Cas nominal

1. `AnthropicAgentProvider` (moteur du terminal, F-39) lit déjà `cache_creation_input_tokens` et
   `cache_read_input_tokens` — il les **additionne** à `input_tokens`. Il les porte désormais
   **aussi** séparément dans `AgentTurn`, sans rien retirer du total existant.
2. `AtelierChatService` cumule les quatre natures sur le tour et les passe au décompte.
3. `AnthropicManagedAgentProvider.getSessionUsage` porte de même la ventilation dans `SessionUsage` ;
   elle sert au **repli** du chemin Managed Agents, quand le fournisseur ne rapporte pas de coût.
4. `AnthropicProvider` (`/chat`, `/ask`) lit les deux champs de cache s'ils sont présents ; ils
   valent 0 tant que la passerelle ne pose pas de `cache_control`, et le comportement est alors
   strictement identique à aujourd'hui.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Champ de cache absent de la réponse | 0 — jamais d'échec, jamais de valeur devinée | — |
| Champ de cache non numérique | 0 (lecture défensive `asLong(0)`, déjà le patron du dépôt) | — |
| Tokens de cache rapportés en négatif | ramenés à 0 avant tout calcul | — |

---

## Critères d'acceptation

- [ ] Un tour d'agent qui lit 100 000 tokens en cache et en écrit 5 000 déclare bien ces deux
      volumes séparément, et le total d'entrée reste `input + lecture + écriture` (aucun volume perdu
      dans F-16 / F-61).
- [ ] Le décompte facturé d'un tour majoritairement servi par le cache s'effondre par rapport au
      décompte d'avant F-63, à volumes identiques.
- [ ] `/chat` et `/ask` se comportent exactement comme avant quand aucun cache n'est rapporté.
- [ ] Le repli du chemin Managed Agents (fournisseur muet sur le coût) facture les natures à leur
      prix au lieu de tout mettre en entrée.

---

## Plan de test minimal

**Unitaires**
- `AnthropicAgentProviderTest` : la ventilation est extraite (cas déjà couvert par le test existant
  qui vérifie le total — le total reste vrai, la ventilation s'ajoute).
- `AnthropicManagedAgentProviderTest` : `SessionUsage` porte lecture et écriture séparément, le
  total d'entrée reste inchangé (test existant conservé).
- `AnthropicProviderTest` : absence de champs de cache → 0.

**Intégration**
- `AtelierChatServiceTest` : les natures cumulées sur un tour de plusieurs itérations arrivent au
  décompte ; le facturé d'un tour à fort cache est très inférieur au volume traité.

**Isolation utilisateur**
- Inchangée : aucun nouvel accès aux données, le décompte reste appelé avec l'utilisateur du
  contexte de sécurité.

---

## Tables / endpoints / composants impactés

Aucune table, aucun endpoint. `AgentTurn`, `SessionUsage`, `ChatCompletionResult`,
`AnthropicAgentProvider`, `AnthropicManagedAgentProvider`, `AnthropicProvider`,
`AtelierChatService`, `AtelierSessionService`.

---

## Préoccupations transversales

| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| Auth / Principal | non | — |
| Contexte tenant | non | aucun accès données ajouté |
| Plans / limites | **oui** | le décompte (`QuotaService.recordUsage`) et le plafond de tour `AtelierTurnBudget` — qui reste exprimé en **tokens traités**, unité que la boucle mesure : SF-63-02 ne change pas ce plafond |
| Navigation / routing | non | — |

---

## Hors périmètre

Poser des `cache_control` là où il n'y en a pas (ce serait une optimisation de coût, pas un
décompte) ; changer le plafond de tour de la boucle maison ; tout montant.
