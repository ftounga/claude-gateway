# Mini-spec — F-39 / SF-39-14 — Déléguer la lecture, et rien d'autre

## Identifiant

`F-39 / SF-39-14`

## Feature parente

`F-39` — L'Atelier comme harnais

## Statut

`done` — livrée le 2026-09-06 (PR #242)

## Date de création

2026-09-06

## Branche Git

`feat/SF-39-14-sous-agents`

---

## Objectif

> Permettre à l'agent de **déléguer une exploration** à une sous-boucle qui lit, cherche et rend une
> réponse courte — pour que lire quarante fichiers ne remplisse plus le contexte du travail
> principal.

---

## Déclencheur

Fin du **lot 7** du cadrage F-39. Le cadrage lui-même signale ce lot comme le plus risqué :
*« complexité forte, dépense multipliée. À re-cadrer à son tour ; le budget de session doit le borner
avant tout dev. »* Ce préalable est levé : SF-39-15 a livré le plafond de consommation par message.

Deux faits ont resserré le périmètre, et il faut les dire avant de décrire ce qui est livré.

**1. Les sous-agents sont désactivés en production.** `APP_ATELIER_AGENT_SUBAGENTS_ENABLED=false`
dans la configmap : côté Managed Agents, la capacité existe depuis F-35 et **personne ne s'en sert**.
Reproduire à l'identique une capacité que l'usage n'a pas réclamée serait construire pour construire.

**2. Le besoin réel est étroit et identifiable.** Dans l'usage mesuré, ce qui déborde le contexte
n'est pas le travail — c'est la **lecture**. Explorer une arborescence, chercher où une fonction est
appelée, lire quarante migrations pour en résumer trois lignes. C'est là, et seulement là, qu'une
sous-boucle paie : elle absorbe le volume et ne rend que la conclusion.

---

## Comportement attendu

### Cas nominal

1. L'agent appelle `explore` avec une **question** et, facultativement, une portée (un chemin).
2. Une **sous-boucle** démarre, avec :
   - sa propre conversation, vide au départ — elle ne voit pas le fil principal ;
   - un jeu d'outils **en lecture seule** : `read_file`, `list_files`, `search_files` (ou `bash`
     restreint aux lectures, voir D2 — écarté) ;
   - la question comme unique consigne, plus l'instruction de répondre court.
3. Elle tourne jusqu'à sa réponse, dans ses propres bornes (§Bornes).
4. Sa **réponse seule** revient à l'agent principal comme résultat d'outil. Ni les fichiers lus, ni
   les étapes intermédiaires n'entrent dans le contexte principal — c'est tout l'intérêt.
5. Sa consommation est **ajoutée à celle du tour** : le plafond par message (SF-39-15) et le quota la
   comptent comme le reste.

### Ce que la sous-boucle ne peut pas faire

| Interdit | Pourquoi |
|---|---|
| Écrire un fichier | Deux agents qui écrivent le même fichier, c'est la question du fichier tenu des deux mains — que F-31 a mis trois subfeatures à trancher. Hors de ce lot. |
| Exécuter une commande (`bash`) | Elle passerait par la **porte de confirmation**, et l'utilisateur se verrait demander d'autoriser des commandes venues d'un agent dont il ignore l'existence. |
| Déléguer à son tour | Une sous-boucle qui délègue ouvre une récursion dont la dépense n'est plus lisible. |
| Poser un plan | Le plan est celui du travail principal, et il n'y en a qu'un. |

### Bornes

| Borne | Valeur | Pourquoi |
|---|---|---|
| Délégations par tour | 3 | Au-delà, c'est le travail principal qu'il faut redécouper |
| Itérations d'une sous-boucle | 10 | Une exploration qui n'aboutit pas en dix lectures ne se termine pas en vingt |
| Longueur de la réponse rendue | 4 000 caractères | C'est un résumé ; au-delà, on aurait aussi bien lu les fichiers |
| Budget de temps | celui du tour | Une sous-boucle ne survit jamais au tour qui l'a lancée |
| Consommation | celle du tour | Comptée dans le plafond par message |

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Question absente ou vide | Résultat d'outil en erreur, tour poursuivi | 200 |
| Quatrième délégation dans le même tour | Refus **dit à l'agent** (« limite de délégations atteinte »), tour poursuivi | 200 |
| Sous-boucle sans réponse après 10 itérations | Ce qu'elle a produit est rendu, avec la mention de la limite | 200 |
| Budget de temps du tour épuisé | La sous-boucle s'arrête à sa frontière sûre, comme la boucle principale | 200 |
| Interruption utilisateur | La sous-boucle s'arrête ; le tour rend le message d'interruption habituel | 200 |
| Panne fournisseur dans la sous-boucle | Erreur rendue comme résultat d'outil — le tour principal n'échoue pas pour autant | 200 |

**Aucune délégation ne fait échouer le tour principal.** C'est une aide ; quand elle échoue, l'agent
doit pouvoir faire le travail lui-même.

---

## Critères d'acceptation

- [ ] L'outil `explore` est déclaré au modèle, sur les deux cibles.
- [ ] La sous-boucle n'a que des outils de lecture — jamais `write_file`, `edit_file`, `bash`,
      `set_plan` ni `explore`.
- [ ] Seule la **réponse** de la sous-boucle entre dans le contexte principal.
- [ ] La consommation de la sous-boucle s'ajoute à celle du tour (quota et plafond).
- [ ] Au plus 3 délégations par tour ; la quatrième est refusée avec un motif lisible.
- [ ] Au plus 10 itérations par sous-boucle ; la limite est dite dans la réponse rendue.
- [ ] La réponse rendue est bornée à 4 000 caractères.
- [ ] Une interruption arrête la sous-boucle.
- [ ] Une panne dans la sous-boucle ne fait pas échouer le tour principal.
- [ ] Isolation `user_id` inchangée : la sous-boucle travaille sur le même workspace **déjà possédé**.
- [ ] Zéro régression : un tour sans `explore` est inchangé.

---

## Périmètre

### Hors scope

- Le **parallélisme**. Les délégations sont séquentielles (D1).
- L'écriture, l'exécution, la délégation récursive (voir tableau ci-dessus).
- Toute reprise de F-35 (sous-agents Managed Agents) : ce lot ne touche pas ce chemin, et ne
  réactive pas son flag.

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Normalisation |
|-------|-------------|-------------|---------------|
| `question` | Oui | 2 000 | `trim()` ; vide ⇒ erreur d'outil |
| `path` | Non | 1 000 | `trim()` ; sert de portée indicative dans la consigne |

---

## Technique

### Endpoint(s)

Aucun. Une étape de progression `explore` s'ajoute au flux existant.

### Tables impactées

Aucune. La trajectoire d'outils (SF-39-03) enregistre l'appel comme les autres.

### Migration Liquibase

- [x] **Non applicable.**

### Classes impactées

| Classe | Changement |
|--------|-----------|
| `atelier/AtelierExploration` | **Nouveau** — la sous-boucle, ses bornes, sa consigne |
| `atelier/AtelierChatService` | Outil `explore`, comptage, garde des 3 délégations |
| `atelier/AtelierProperties` | `max-delegations` (3), `max-delegation-iterations` (10) |

### Composants Angular

Aucun : l'étape apparaît dans la liste des étapes existante.

---

## Préoccupations transversales

| Préoccupation | Concernée | Composants impactés |
|--------------|-----------|--------------------|
| Auth / Principal | Non | — |
| **Contexte tenant** | **Oui** | La sous-boucle lit les fichiers du **même workspace**, résolu une seule fois par `requireOwned` en tête du tour et transmis tel quel. Aucun identifiant n'est reconstruit, aucun nouveau chemin d'accès n'est ouvert. Composants revus : `AtelierChatService.runLoop`, `AtelierExploration`, `RunnerToolGateway` (appelé avec le même `workspaceId`). |
| **Plans / limites** | **Oui** | La consommation de la sous-boucle est **ajoutée aux compteurs du tour**, donc prise en compte par `QuotaService.recordUsage` (F-10) et par le plafond par message (SF-39-15) — y compris sa projection, qui majore l'itération suivante. Vérifié par test : un tour avec délégation décompte la somme. |
| Navigation / routing | Non | — |

---

## Plan de test

### Tests unitaires

- [ ] La sous-boucle ne reçoit que des outils de lecture (vérifié sur la requête transmise).
- [ ] Seule sa réponse revient dans le contexte principal (les fichiers lus n'y sont pas).
- [ ] Sa consommation s'ajoute à celle du tour.
- [ ] Quatrième délégation ⇒ refus lisible, tour poursuivi.
- [ ] Sous-boucle sans réponse après 10 itérations ⇒ ce qui a été produit, plus la mention.
- [ ] Réponse tronquée à 4 000 caractères.
- [ ] Question vide ⇒ erreur d'outil, tour poursuivi.
- [ ] Panne fournisseur dans la sous-boucle ⇒ le tour principal aboutit.
- [ ] Interruption ⇒ la sous-boucle s'arrête.
- [ ] Non-régression : un tour sans `explore` est inchangé.

### Tests d'intégration

- [ ] Couvert par les tests existants du flux : le contrat de l'endpoint ne change pas.

### Isolation workspace

- [x] Applicable — la sous-boucle n'accède qu'au workspace déjà possédé ; tests existants verts.

---

## Dépendances

- `SF-39-13` (plan) — done · `SF-39-15` (plafond par message) — done, **préalable posé par le cadrage**

---

## Notes et décisions

**D1 — Séquentiel, pas parallèle.** Le parallélisme est ce qui rend les sous-agents intéressants *et*
ce qui les rend dangereux : trois sous-boucles simultanées, ce sont trois fois la dépense au même
instant, une projection de plafond qui ne majore plus rien, et une interruption qui doit atteindre
trois exécutions. Le gain — du temps d'horloge — n'est pas ce qui manque aujourd'hui. On prend le
bénéfice de contexte sans le coût de coordination.

**D2 — Lecture seule, et `bash` exclu même en cible `RUNNER`.** Une sous-boucle qui exécute
passerait par la porte de confirmation (SF-38-08), et l'utilisateur se verrait demander d'autoriser
une commande venue d'un agent dont il ignore l'existence. Rattacher la demande à son délégant
supposerait de repenser tout l'affichage de la porte — pour un besoin que personne n'a exprimé.

**D3 — Un outil, pas un roster.** F-35 a montré que le fournisseur n'accepte qu'une entrée `self`
dans son roster, et que le plafond de délégation doit se dire dans le prompt. Ici, la question est
plus simple : il n'y a **qu'un type** de sous-boucle, l'exploration. Nommer l'outil `explore` plutôt
que `delegate` dit ce qu'il fait, et referme la porte aux usages qu'on n'a pas bornés.

**D4 — La dépense de la sous-boucle appartient au tour.** Elle n'a ni quota propre, ni plafond
propre : elle consomme celui du message qui l'a lancée. C'est le seul modèle qui garde le plafond par
message honnête — sans quoi une délégation serait un moyen de le contourner.
