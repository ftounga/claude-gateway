# Mini-spec — F-177 / SF-177-01 — La règle écrite s'applique partout

## Identifiant
`F-177 / SF-177-01` — feature parente `F-177` *La gouvernance se pilote depuis le terminal* (cadrage
`CADRAGE-F-177-la-gouvernance-se-pilote-du-terminal.md`, décision D1).
Branche : `feat/SF-177-01-regle-ecrite-partout`. Statut : `in-progress`. Date : 2026-10-06.

## Objectif
Que le `GOUVERNANCE.md` du **poste** (racine) puis celui du **sujet** soient injectés dans la consigne
de **chaque tour**, au même rang que `CLAUDE.md`, pour qu'une règle écrite une fois vaille partout.

## Comportement attendu

### Cas nominal
1. **Sujet d'un poste** (cible `RUNNER`, chemin de projet non vide, pas le terminal du poste) :
   - le `GOUVERNANCE.md` de la **racine du poste** est lu (cible sans chemin de projet) et injecté
     encadré : `--- Règles du client — poste (…) ---` … `--- Fin des règles du poste (GOUVERNANCE.md) ---` ;
   - puis le `GOUVERNANCE.md` **du sujet**, encadré `--- Règles du client — sujet (…) ---` … `--- Fin … ---`.
2. **Terminal du poste** : son propre `GOUVERNANCE.md` (= celui de la racine) sous l'en-tête « poste » ;
   aucune section sujet.
3. **Projet hébergé (SANDBOX)** : pas de poste ; seul son `GOUVERNANCE.md` sous l'en-tête « sujet ».
4. **Place** : après `CLAUDE.md`, avant les règles des paquets (« Règles de gouvernance (paquets actifs) »)
   — ordre poste → sujet, comme la hiérarchie `CLAUDE.md` de Claude Code.
5. **Bornes** : 8 000 caractères par fichier ; au-delà, coupe dite (« règles tronquées : ouvre
   GOUVERNANCE.md pour la suite »). Fichier absent ou vide → section absente (les deux bornes ou aucune).
6. **Suivi `prompt_source_files`** (F-148) : `GOUVERNANCE.md` rejoint les fichiers cœur relus après
   chaque tour ; la copie racine vue d'un sujet se range sous le chemin réservé `/__host_governance__`.
   Une fois le cache amorcé, servi depuis la base — aucun aller-retour runner, préfixe **byte-stable**
   (cache F-134 préservé). Un `not_found` exact à la racine retire la copie (une règle supprimée cesse
   de s'appliquer) ; une machine muette ne touche à rien.
7. Préparation SF-177-02 : `PromptSourceStore.putHostGovernance` / `putFile` rangent directement un
   contenu validé pour tous les projets amorcés du poste (non appelés dans cette SF).

### Cas d'erreur
| Situation | Comportement |
|---|---|
| Fichier absent (`not_found`) | section omise ; copie racine rangée supprimée |
| Machine injoignable / lecture en erreur | section omise (lecture directe) ou copie précédente servie (cache) ; jamais d'exception |
| Fichier > 8 000 car. | tronqué + mention de la coupe |
| Projet d'un autre poste | lit la racine de **son** poste uniquement (cible bâtie depuis l'entité possédée) |

## Critères d'acceptation
- [x] Une règle du `GOUVERNANCE.md` racine est dans la consigne d'un tour de **n'importe quel** sujet du même poste.
- [x] Elle n'est **pas** dans la consigne d'un projet d'un **autre** poste.
- [x] Ordre poste → sujet ; bornes et coupe dite.
- [x] Cache amorcé : second tour sans lecture runner, consigne byte-identique.
- [x] Une règle supprimée à la racine disparaît après rafraîchissement.

## Plan de test
- **Unitaires service** `AtelierChatServiceGovernanceFileTest` : poste+sujet ordonnés ; autre sujet même
  poste ; autre poste (isolation) ; terminal du poste ; borne ; cache byte-identique sans aller-retour ;
  suppression ; SANDBOX.
- **Non-régression** : suites `atelier.**` et `governance.**` (2 333 tests) ; `RunnerGuardTest` : le compte
  de lectures d'amorçage passe de 6 à 7 (+ `GOUVERNANCE.md`).
- **Isolation utilisateur** : la cible racine est bâtie depuis le `Workspace` chargé par `requireOwned`
  (`user_id`) — jamais depuis une entrée client ; cache filtré `user_id` + `workspace_id`.

## Impacts
Backend : `AtelierChatService` (injection, bornes, fichiers cœur), `PromptSourceStore` (chemin réservé,
relecture racine, `putHostGovernance`/`putFile`). Aucune table, aucune migration, aucun endpoint.

### Préoccupations transversales
- **Contexte tenant** : ✔ — composants : `AtelierChatService.buildPrompt` (cible bâtie depuis
  l'entité possédée), `PromptSourceStore` (lignes par `user_id` + `workspace_id`). Pas de nouveau moyen
  de résoudre le tenant.
- Auth / Plans / Navigation : non.

## Hors périmètre
Proposition/validation d'une règle (SF-177-02), skills par `/` (SF-177-03), écran (SF-177-04).

## Arbitrages (réversibles)
- Bornes 8 000 car. par fichier (cadrage D1).
- Le gabarit `GOUVERNANCE.md` déposé par le paquet « savoir durable » (~5 Ko) est désormais injecté dans
  les sujets gouvernés : c'est le fichier de règles du client (D1) ; littéral stable donc cache préservé.
- Seul un `not_found` exact retire la copie racine (pas un échec de transport).
