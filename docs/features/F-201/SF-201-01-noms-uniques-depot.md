# Mini-spec — [F-201 / SF-201-01] Noms uniques au dépôt (hébergé + poste)

## Identifiant
`F-201 / SF-201-01` — Feature parente `F-201` (Médias collés : noms uniques et marqueurs dans le texte) — Statut `in-review` — Créée 2026-10-16
Branche : `feat/SF-201-01-noms-uniques-depot` — aucune dépendance. Cadrage : `CADRAGE-F-201-medias-colles-comme-claude-code.md`.

## Objectif
Un dépôt n'écrase **jamais** un fichier déjà déposé dans le même terminal : chaque fichier reçoit un nom unique, choisi par le serveur (source de vérité), en hébergé comme sur le poste.

## Comportement nominal
`WorkspaceDepositService.deposit` assainit le nom (inchangé, `DepositFileName.sanitize`), puis le passe à `DepositNameAllocator.allocate(nom, pris, maintenant)` (fonction pure) :

| Nom reçu | Nom déposé |
|---|---|
| générique (`image.png`, `image (2).png`, `blob`, `Pasted image.png`, insensible à la casse) | `capture-AAAAMMJJ-HHMMSS.<ext>` (heure de Paris ; extension conservée, `.png` à défaut) |
| tout autre nom, libre | inchangé |
| nom déjà pris | suffixe avant l'extension : `rapport-2.pdf`, `rapport-3.pdf`… (même règle pour une capture prise dans la même seconde : `capture-…-2.png`) |

« Pris » = l'union, comparée sans casse, de :
- les chemins **déjà déposés dans ce terminal** (`atelier_deposited_file`, filtre `(user_id, workspace_id)`, consommés ou non) ;
- en hébergé, les fichiers présents sous `entrees/` du stockage (`WorkspaceService.depositedHostedNames`, isolé par `requireOwned`) — un fichier écrit par l'agent compte aussi ;
- les noms déjà attribués dans **le même lot** (plusieurs captures collées d'un coup).

Le chemin rendu (`DepositResponse.files[].path`) et enregistré porte le nom final : l'agent reçoit des chemins distincts.

## Cas d'erreur
| Situation | Comportement attendu |
|---|---|
| Nom invalide (vide, `..`, contrôle) | 400 `invalid_name`, inchangé |
| Nom long + suffixe dépassant 255 caractères | la base est tronquée pour que le nom final tienne en 255 |
| Fichier sur le poste créé hors dépôt (pas en base) | non détecté (pas d'aller-retour runner supplémentaire) — un nom générique reste toujours horodaté, donc les collages ne s'écrasent plus |
| Poste hors ligne / bornes | refus nommés inchangés (409 / 413) |

## Critères d'acceptation
- [ ] CA1 Deux dépôts successifs de `image.png` dans un même terminal produisent deux chemins distincts `entrees/capture-…png` (hébergé) / `.atelier/entrees/capture-…png` (poste) ; aucune écriture ne vise deux fois la même clé.
- [ ] CA2 Un lot de 3 `image.png` collés la même seconde → `capture-T.png`, `capture-T-2.png`, `capture-T-3.png`.
- [ ] CA3 `rapport.pdf` déposé deux fois → `rapport.pdf` puis `rapport-2.pdf` ; un nom libre est conservé tel quel.
- [ ] CA4 Isolation : seuls les dépôts du couple `(user_id, workspace_id)` comptent comme « pris » (requête filtrée), le listage hébergé passe par `requireOwned`.
- [ ] CA5 Non-régression : `WorkspaceDepositServiceTest` et la suite backend complète restent vertes.

## Plan de test
- `DepositNameAllocatorTest` (nouveau, unitaire) : générique → horodaté (variantes `image (3).png`, `IMAGE.PNG`, `blob`, sans extension), nom libre conservé, suffixes `-2/-3`, comparaison sans casse, troncature à 255.
- `WorkspaceDepositServiceTest` : deux `image.png` hébergés → chemins distincts ; lot de 3 sur le poste → 3 chemins distincts ; nom déjà en base → `-2` ; la requête des noms pris est appelée avec `(userId, workspaceId)`.
- Isolation utilisateur : requête repository filtrée `(userId, workspaceId)` ; vérification d'arguments.
- Non-régression : `mvn -pl backend test` complet.

## Impacts
- Tables : aucune nouvelle table, aucune migration (lecture de `atelier_deposited_file.path`).
- Endpoints : `POST /api/workspaces/{id}/deposit` — contrat inchangé ; seul le `path` rendu peut différer du nom envoyé (c'était déjà le cas après assainissement).
- Composants : `DepositNameAllocator` (nouveau), `WorkspaceDepositService` (+ `Clock`), `AtelierDepositedFileRepository` (+ `findPathsByUserIdAndWorkspaceId`), `WorkspaceService` (+ `depositedHostedNames`).
- Préoccupations transversales : **Contexte tenant** — composants qui lisent les dépôts : `WorkspaceDepositService` (nouvelle requête filtrée user+workspace), `DepositConsumptionService` (inchangé), `WorkspaceService.depositedHostedNames` (via `requireOwned`). Auth, plans, navigation : non.

## Arbitrages (gate 🟠 réversible — décidé par défaut)
- **Heure de Paris** pour l'horodatage (l'heure que voit le PO) ; UTC écarté (décalage déroutant). Réversible : une constante.
- **Pas de sonde du poste** pour un fichier préexistant non déposé : base des dépôts + horodatage des noms génériques suffisent (les 84 écrasements observés sont tous des `image.png`) ; une sonde coûterait un aller-retour runner par fichier. Réversible.
- **Suffixe `-N`** (et non ` (N)`) : ni espace ni parenthèse dans un chemin que l'agent manipule en bash.

## Hors périmètre
- Frontend (marqueurs `[Image #N]`) : SF-201-02.
- Renommage des anciens `image.png` déjà déposés.
