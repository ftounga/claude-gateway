# Mini-spec — [F-174 / SF-174-04] Les pièges et les échéances d'abord

## Identifiant

`F-174 / SF-174-04`

## Feature parente

`F-174` — La carte qui répond (cadrage `CADRAGE-F-174-la-carte-qui-repond.md`, D1→D10 validées le 2026-10-04)

## Statut

`done`

## Date de création

2026-10-04

## Branche Git

`feat/SF-174-04-pieges-et-echeances`

---

## Objectif

Dans le bloc de faits du tour, faire passer en tête les échéances proches puis les pièges qui concernent une ressource citée par la question, et les marquer comme tels (D7).

---

## Comportement attendu

### Cas nominal

1. La recherche (SF-174-03) reçoit la date du jour. Si la question **touche** des ressources de l'index (nom ou identifiant cité), avant les quatre maillons D5 :
   - **échéances** (`ECHEANCE`) concernant une ressource touchée, dues d'ici **14 jours** ou dépassées depuis **14 jours au plus**, triées par date : **toujours citées** (dans la borne totale de 20) ;
   - **pièges** (`PIEGE`) concernant une ressource touchée : ensuite, au plus la moitié des places (10 sur 20).
2. « Concerner » : le fait porte un identifiant de la ressource, ou cite son nom / un identifiant comme mot entier.
3. Le bloc marque chaque fait selon sa nature, quel que soit le maillon qui l'a retenu : `⟨piège⟩`, `⟨échéance AAAA-MM-JJ — dans N j⟩` / `— aujourd'hui` / `— dépassée depuis N j`. S'il y a au moins un piège, une consigne d'une ligne le précède : « Ce qui est marqué « piège » … tiens-en compte AVANT d'agir. »
4. Le journal (SF-174-01) compte pièges et échéances par ces marques.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Aucune ressource touchée | Pas de priorité ; maillons D5 inchangés |
| Pas de date du jour | Pas d'échéance mise en avant (pièges seuls) |
| Échéance lointaine (> 14 j) ou très ancienne (> 14 j dépassée) | Non mise en avant (reste trouvable par les maillons D5) |
| Trop de pièges | Bornés à la moitié des places |

---

## Critères d'acceptation

- [x] Ordre : échéances proches (par date), pièges, puis identifiant > entité > sémantique > lexical.
- [x] Une échéance lointaine ou un piège d'une autre ressource n'est pas mis en avant.
- [x] Pièges bornés à 10 sur 20.
- [x] Marques et consigne présentes dans le bloc.

---

## Périmètre

### Hors scope (explicite)

- L'outil `carte_chercher` (SF-174-05) — il réutilise ces marques.

---

## Technique

### Composants backend

- `HostMapSearch` (raisons `DEADLINE`, `PITFALL`, `search(..., today)`, `concerns`), `HostMapFactRepository.findByUserIdAndHostIdAndKindInOrderByPathAscLineNoAsc` (filtré `(user_id, host_id)`), `HostMapFactsBlock.marks`, `HostMapKnowledgeProvider` (passe la date).

### Tables / endpoints / écrans

Aucun.

### Préoccupations transversales

| Préoccupation | Cochée | Composants |
|---|---|---|
| Contexte tenant | Oui | nouvelle requête `findByUserIdAndHostIdAndKindIn…` filtrée `(user_id, host_id)` ; aucun autre point |
| Auth, plans, routing | Non | — |

---

## Plan de test

- [x] `HostMapSearchPriorityTest` : ordre et tri des échéances, exclusions, sans ressource / sans date, borne des pièges, marques du bloc.
- [x] `HostMapKnowledgeProviderHybridTest` adapté (date passée).
- [x] Suite complète verte.

## Dépendances

SF-174-03 mergée.

## Notes et décisions

- **Arbitrage (réversible)** : une échéance dépassée depuis 14 jours au plus reste mise en avant (un jeton expiré bloque autant qu'un jeton qui expire).
- **Arbitrage (réversible)** : les pièges sont bornés à la moitié des places, pour que le bloc réponde aussi à la question.
