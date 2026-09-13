# F-111 — Le runner se met à jour d'un clic

> Cadrage du 2026-09-13, validé par le PO (« Ok cadre ça et ajoute-le à la vague »).

## 1. Le besoin

> « Est-ce qu'on peut imaginer que l'application annonce quand il y a un runner et se mette à jour
> seul ? […] Je veux pouvoir cliquer sur une mise à jour sur l'application. »

Aujourd'hui : Ctrl+C, `curl` du nouveau `claude-runner.jar` (derrière le proxy du client), relance
avec les bonnes options. Oublié une fois, et le terminal Teams a tourné sans ses outils (runner trop
ancien, `unsupported_tool`).

## 2. La solution : un lanceur et le vrai runner

`claude-runner.jar` **reste le fichier que l'utilisateur lance**, avec la même commande. Il contient :

1. **Le lanceur** — minuscule, sans code réseau, il reste au premier plan dans le terminal. Il démarre
   le vrai runner comme **processus enfant** (`java -cp versions/<v>/runner.jar …`, entrée et sortie
   héritées : la console reste la même), avec **les mêmes arguments et le même environnement** (proxy,
   `CLAUDE_TEAMS_DEBUG_PORT`, confiance TLS).
2. **Le vrai runner** — tout le travail d'aujourd'hui. Au premier lancement, le lanceur le copie dans
   `~/.claude-runner/versions/<version>/runner.jar`.

**Le code de sortie dit au lanceur quoi faire :**

| Sortie du runner | Code | Le lanceur |
|---|---|---|
| mise à jour prête (fichier `~/.claude-runner/next-version` écrit) | `75` | démarre la version indiquée |
| arrêt demandé (Ctrl+C, coupe-circuit, arrêt depuis la Forge) | `0` ou codes d'arrêt existants | s'arrête aussi |
| plantage | autre | relance la même version, 3 fois au plus en 5 minutes |

`--no-launcher` démarre le runner directement (diagnostic). Le mode `--releve-teams` et `--check`
restent sans lanceur.

## 3. La mise à jour, pas à pas

1. **La version réelle** : le runner déclare à la connexion sa version de construction (numéro
   sémantique + commit + date) et son **niveau de contrat** (F-81) ; aujourd'hui tout vaut `0.0.1`.
2. **La Forge l'affiche** sur le poste (colonne et en-tête, Forge et Vigie) : « Mise à jour disponible
   — 1.4 → 1.6 » avec la liste courte de ce qu'elle apporte, ou « **Mise à jour requise** » quand une
   capacité utilisée manque (ex. `teams` absent). Bouton **« Mettre à jour »**.
3. **Le clic** envoie au runner une commande `update` (WebSocket ou long-polling).
4. **Le runner télécharge** `GET /runner/update/<version>` **par son propre client HTTP** (proxy,
   px, magasin de confiance d'entreprise : ce qui marche déjà pour sa connexion).
5. **Il vérifie** : empreinte SHA-256 **et signature Ed25519** du fichier, contrôlée avec la **clé
   publique embarquée** (`runner/src/main/resources/update-signing-public-key.pem`). Un proxy qui
   intercepte TLS, ou une gateway compromise, ne peut pas faire exécuter un runner modifié. Échec →
   rien n'est installé, et la Forge le dit.
6. **Il attend le calme** : aucune commande, capture (F-91), synchro (F-100) ou téléchargement en cours ;
   la Forge montre « en attente de la fin de la commande » et propose **Forcer**.
7. **Il sort avec le code 75** ; le lanceur démarre la nouvelle version. Le jeton du poste est relu
   (F-46) : **aucun nouveau code**. Pendant la bascule (~10 s), la Forge affiche « mise à jour en
   cours », pas « hors ligne ».
8. **Contrôle de santé** : la nouvelle version doit se reconnecter en **90 s**. Sinon, ou après
   3 plantages, le lanceur **revient à la version précédente** et le runner le signale à la
   reconnexion (« mise à jour vers 1.6 échouée, retour à 1.4 » + motif).

## 4. Les gardes

- **Signature obligatoire** : aucune installation d'un fichier non signé, même si la gateway le sert.
- **Jamais de retour à une version plus ancienne**, sauf le retour automatique après échec.
- **Deux versions précédentes** conservées, les plus anciennes supprimées.
- **Seul le propriétaire du poste** (ou l'ADMIN) peut déclencher ; chaque mise à jour est journalisée
  (qui, quand, de → vers, résultat).
- **Java** : si une version exige un Java plus récent que celui du poste (paquets macOS/Windows avec
  Java intégré compris), la Forge affiche « mise à jour manuelle requise » avec la commande.
- **Le lanceur lui-même** change rarement ; s'il le faut, il est remplacé **au prochain démarrage
  manuel** (jamais pendant qu'il tourne, fichier verrouillé sous Windows).

## 5. La signature à la construction

- Clé privée Ed25519 dans **AWS Secrets Manager** : `claude-gateway/runner-update-signing-key`
  (créée le 2026-09-13, jamais dans le dépôt ni dans le cluster).
- Clé publique dans le dépôt (§3.5).
- La construction de l'image backend signe le runner via un **secret de build Docker**
  (`--secret id=runner_signing_key`) et publie `runner.jar`, `runner.jar.sha256`, `runner.jar.sig` et
  un manifeste de version. **Sans clé fournie, la construction échoue** (en production) — une image
  qui servirait un runner non signé serait inutilisable pour la mise à jour.
- Le script de déploiement est adapté par le PO/l'orchestrateur (pas par un agent).

## 6. La transition, dite franchement

Les runners déjà installés n'ont pas de lanceur : **une dernière mise à jour manuelle** (le `curl`
habituel) est nécessaire. La Forge l'affiche pour ces postes (« runner sans mise à jour automatique :
mise à jour manuelle une dernière fois ») avec la commande exacte selon le système.

## 7. Découpage

| SF | Titre | Contenu |
|---|---|---|
| SF-111-01 | La version réelle et la Forge qui l'affiche | Version de construction et contrat déclarés à la connexion, stockés sur le poste (migration numéro 101 ou premier libre), comparaison avec la version servie, « disponible / requise / manuelle une dernière fois » dans la colonne et l'en-tête (Forge et Vigie) |
| SF-111-02 | Le lanceur | Mode lanceur de `claude-runner.jar`, processus enfant à entrées-sorties héritées, mêmes arguments et environnement, codes de sortie, relance après plantage, `--no-launcher`, installation initiale dans `versions/`, Windows/macOS/Linux |
| SF-111-03 | Signer et servir les versions | Signature Ed25519 à la construction (secret de build), manifeste, `GET /runner/update/<version>` + empreinte + signature, vérification côté runner avec la clé embarquée |
| SF-111-04 | La commande de mise à jour | Bouton « Mettre à jour », commande `update`, téléchargement par le client HTTP du runner, attente du calme et **Forcer**, sortie 75, état « mise à jour en cours », journal |
| SF-111-05 | Santé et retour arrière | Reconnexion en 90 s, 3 plantages, retour à la version précédente, rapport à la reconnexion, rétention de deux versions |

## 8. Préoccupations transversales

- **Sécurité : oui**, centrale (signature, qui peut déclencher, pas de retour en arrière).
  Composants : runner (`RunnerMain`, client HTTP, canal), `RunnerDownloadController`, `backend/Dockerfile`,
  canal WebSocket et long-polling, écrans Forge/Vigie.
- **Auth : oui** — l'endpoint de mise à jour suit l'accès public actuel du téléchargement, mais le
  **déclenchement** est authentifié et réservé au propriétaire.
- **Navigation : non.**

## 9. Hors périmètre

- Installer le runner comme service du système (droits administrateur rarement accordés).
- Mise à jour automatique sans clic (la nuit) : possible plus tard, même mécanisme.
- Mise à jour du Java embarqué des paquets.
