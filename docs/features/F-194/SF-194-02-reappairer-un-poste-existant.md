# Mini-spec — F-194 / SF-194-02 Réappairer un poste existant depuis sa carte

## Identifiant

`F-194 / SF-194-02`

## Feature parente

`F-194` — Un poste actif ne perd jamais sa connexion

## Statut

`in-progress`

## Date de création

2026-10-12

## Branche Git

`feat/SF-194-02-reappairer-un-poste`

---

## Objectif

Depuis la carte d'un poste (écran Postes), obtenir un nouveau code d'appairage **pour ce poste**, sans créer de client.

## Contexte (constat du 2026-10-12)

Jeton EDENRED expiré : pour obtenir un code, le PO a dû créer un faux client « toto », appairer avec, puis le supprimer — ce qui a supprimé le jeton obtenu. Depuis F-72, « Connecter un poste » ouvre le dialogue en **mode poste**, qui ne propose que la création ; le choix d'un poste existant et le bouton « Réappairer cette machine » (F-82) ne vivent qu'en mode projet, qu'aucun écran n'ouvre plus. L'API `POST /api/runner-hosts/{id}/pairing-code` existe déjà.

## Comportement attendu

### Cas nominal

1. Menu « … » de la carte d'un poste réel → **« Réappairer ce poste… »**.
2. Le dialogue d'appairage s'ouvre en mode poste **sur ce poste** (`data.hostId`, `data.hostName`) : titre « Réappairer « EDENRED » », étape 2 déjà faite (« Poste « EDENRED ». »), aucun champ de nom, aucune création de poste.
3. Après la vérification réseau (étape 1), le parcours ouvre l'étape « code » ; le code est généré pour ce poste ; la suite (téléchargement, lancement avec `--code`) est inchangée.
4. Le relevé d'état du poste démarre dès l'ouverture : si le poste a encore un jeton valide, l'encart de reprise F-82 (« aucun code n'est nécessaire » + « Réappairer cette machine ») s'affiche comme en mode projet.
5. La conclusion ne dit pas « Aucun projet n'existe encore » : le poste garde ses projets.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Poste « Hébergé » (virtuel, sans id) | Pas d'entrée de menu (la carte n'a pas de menu de poste) |
| Poste supprimé entre-temps | La génération du code échoue : message d'erreur existant de l'étape « code » |
| Accès Forge refusé (403) | Comportement existant (F-85) |

## Critères d'acceptation

- [ ] La carte d'un poste réel propose « Réappairer ce poste… » dans son menu.
- [ ] Le clic ouvre le dialogue avec `{ hostId, hostName }` et sans `workspaceId`.
- [ ] Dans ce mode, le titre nomme le poste, l'étape 2 est faite, le champ « Nom du client » et le bouton « Créer le poste » sont absents, `createRunnerHost` n'est jamais appelé.
- [ ] Le code est demandé via `createHostPairingCode(hostId)` du poste existant.
- [ ] Le relevé d'état démarre à l'ouverture (`getHostRunnerStatus(hostId)`).
- [ ] « Connecter un poste » (création) est inchangé.

## Plan de test

- Unitaires Karma `runner-pairing-dialog.component.spec.ts` : mode réappairage (titre, étape faite, pas de champ, code demandé pour l'id fourni, statut relevé, pas de création).
- Unitaires Karma `postes.component.spec.ts` : l'entrée ouvre le dialogue avec `{ hostId, hostName }`.
- Non-régression : suites des deux composants + suite frontend complète.
- Isolation utilisateur : inchangée — l'API `pairing-code` vérifie déjà la propriété du poste (`requireOwned`, 404 sinon).

## Impacts

- Composants : `PostesComponent` (menu + `reconnectHost`), `RunnerPairingDialogComponent` (mode réappairage).
- Endpoints, tables : aucun.
- Préoccupations transversales : Navigation / routing — non (dialogue, aucune route). Auth, tenant, plans : non.
- Design system : `mat-menu-item` + `mat-icon` existants, textes uniquement.

## Hors scope

- Réappairage depuis la Vigie.
- Détection automatique « jeton expiré » sur la carte (le relevé d'état existant suffit).
