# Mini-spec — F-194 / SF-194-03 Un code fourni remplace le jeton, et le runner nomme son poste

## Identifiant

`F-194 / SF-194-03`

## Feature parente

`F-194` — Un poste actif ne perd jamais sa connexion

## Statut

`in-progress`

## Date de création

2026-10-12

## Branche Git

`feat/SF-194-03-code-prime-et-poste-nomme`

---

## Objectif

Un `--code` passé au runner remplace le jeton stocké, et le runner dit à quel poste il est rattaché, en signalant le cas où il prend la place d'une autre machine.

## Contexte (constat du 2026-10-12)

- La machine EDENRED s'est réappairée avec un code généré pour FREE. Rien ne l'a signalé : le runner n'affiche pas le nom du poste, et la gateway a remplacé sans un mot la racine de FREE (`infra`) par celle d'EDENRED (`dev`).
- Pour corriger, relancer avec le bon `--code` ne suffisait pas : `RunnerMain.obtainToken` réutilise le jeton stocké dès qu'il existe et **ignore** le code. Il fallait d'abord supprimer `token.json` à la main.

## Comportement attendu

### Cas nominal

**Runner : priorité du code**
1. Un `--code` est fourni et un jeton est stocké : le runner appaire avec le code. En cas de succès, le nouveau jeton remplace l'ancien, et le runner l'annonce (« Un code est fourni : il remplace le jeton stocké. »).
2. Aucun code n'est fourni : le jeton stocké est réutilisé, comme aujourd'hui.

**Gateway : nommer le poste**
3. `POST /runner/pair` renvoie en plus deux champs additifs :
   - `hostName`, le nom du poste ;
   - `previousRootName`, l'ancienne racine du poste quand elle était renseignée et diffère de la racine déclarée, sinon `null`.

**Runner : afficher le poste**
4. Après l'appairage, le runner affiche « Poste : « FREE » ». Si `previousRootName` est renseigné, il ajoute un AVERTISSEMENT : « ce poste était déclaré sur la racine « infra » ; cette machine (racine « dev ») le remplace. Si ce n'est pas le bon poste, générez le code depuis la carte du bon poste. »
5. `hostName` est conservé dans `token.json`. À chaque reprise, le runner affiche « Poste : « X » ». Pour un jeton stocké par une version antérieure, sans `hostName`, il n'affiche rien.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Code fourni refusé (déjà utilisé, expiré, faux) **et** jeton stocké | Avertissement « code refusé : le jeton stocké est conservé », puis reprise avec ce jeton. Le lanceur relance le runner avec ses arguments d'origine, après une mise à jour par exemple : un code consommé ne doit pas arrêter un poste appairé. |
| Code fourni refusé **sans** jeton stocké | Erreur d'appairage, sortie 3 (inchangé) |
| Jeton stocké refusé par la gateway en session | Inchangé : jeton effacé, réappairage si un code est fourni |
| Gateway antérieure, sans les nouveaux champs | Le runner n'affiche ni nom ni avertissement (champs nuls) |
| Runner antérieur face à la nouvelle gateway | Champs ignorés (`@JsonIgnoreProperties`), inchangé |

## Critères d'acceptation

- [ ] Avec un jeton stocké et un `--code` valide, le runner appaire et stocke le nouveau jeton.
- [ ] Avec un jeton stocké et un `--code` refusé (401), le runner reprend avec le jeton stocké et l'annonce.
- [ ] Sans jeton stocké, avec un `--code` refusé : `PairingException`, sortie 3.
- [ ] `PairResponse` porte `hostName` et `previousRootName`. Ce dernier est nul quand la racine est inchangée, quand l'ancienne racine était vide ou quand aucune racine n'est déclarée.
- [ ] Le runner affiche le nom du poste après l'appairage et à la reprise, ainsi que l'avertissement de changement de racine.
- [ ] Contrat : la réponse émise par la vraie gateway est lue par le vrai runner, avec les nouveaux champs.

## Plan de test

- **Runner (unitaires), nouvelle classe `TokenAcquisition`** : les 4 combinaisons de jeton stocké et de code (valide ou refusé), le message de remplacement et le repli.
- **Runner** : `StoredToken` lit `hostName` et `previousRootName`, et un ancien `token.json` reste lisible. Le jeton stocké ne garde pas `previousRootName`.
- **Backend** :
  - `RunnerPairingService` (intégration) : `previousRootName` est renseigné quand la racine change, nul quand elle est identique, vide avant ou non déclarée ; `hostName` est renvoyé ;
  - `RunnerHostServiceTest` : `recordDeclaration` renvoie l'ancienne racine remplacée.
- **Contrat** : `PairingContractTest` lit les nouveaux champs.
- **Isolation** : inchangée. Le code d'appairage porte `user_id` et `host_id`, et `/runner/pair` reste public, authentifié par le code.

## Impacts

- Backend : `PairResponse`, `RunnerPairingService.PairedRunner`, `RunnerPairController`, `RunnerHostService.recordDeclaration` (renvoie l'ancienne racine remplacée).
- Runner : `StoredToken` (+`hostName`, +`previousRootName`), nouvelle classe `TokenAcquisition`, `RunnerMain.obtainToken` et `pairAndStore`. **Mise à jour du runner nécessaire** pour les points 1, 4 et 5.
- Endpoint : `POST /runner/pair`, réponse enrichie de champs additifs.
- Tables : aucune.
- Préoccupations transversales : Auth / Principal, **oui** (échange code contre jeton). Composants impactés :
  - `RunnerPairController` / `RunnerPairingService.redeem` : réponse enrichie, contrôle du code inchangé ;
  - `RunnerTokenAuthenticator` : inchangé ;
  - `RunnerMain` : priorité du code.

  Contexte tenant, plans et navigation : non concernés.

## Hors scope

- Bloquer l'appairage d'une machine différente : la machine peut légitimement changer, on avertit sans interdire.
- Révoquer automatiquement l'ancien jeton du poste lors d'un réappairage.
