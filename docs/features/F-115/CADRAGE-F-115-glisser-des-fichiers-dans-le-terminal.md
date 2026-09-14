# F-115 — Glisser des fichiers dans tous les terminaux, comme dans Claude Code

> Cadrage du 2026-09-14, à la demande du PO. **Cadrage seul : la livraison attend le go du PO.**

## 1. Le besoin

> « Dans tous les terminaux je veux la possibilité de pouvoir glisser tout types de fichiers,
> exactement comme dans Claude Code. »

Dans Claude Code, on dépose un fichier (capture, log, PDF, code, archive…) dans la conversation, et
l'agent y a accès pour le tour. Le produit sait déjà **ajouter des fichiers à un workspace hébergé**
(F-28 / SF-28-13, par le composer de l'Atelier), mais **pas par glisser-déposer dans un terminal**, et
**pas sur un terminal de poste, de projet local ou Teams**.

## 2. Où l'on dépose, et où va le fichier

Le point clé : un terminal n'a pas tous le même « endroit » où poser un fichier. Le dépôt suit la
**cible du terminal**, et l'agent le retrouve **par un chemin**, jamais par un contenu réinjecté.

| Terminal | Cible | Où le fichier est écrit | Comment l'agent le voit |
|---|---|---|---|
| Projet **hébergé** (bac à sable) | workspace S3 | dossier `entrees/` du workspace (mécanisme SF-28-13) | `read_file entrees/<nom>` |
| Projet **local** / **poste** (runner) | machine du client | `<racine>/.atelier/entrees/<nom>` sur le poste, par le runner (`write_file`, contrat existant) | `read_file .atelier/entrees/<nom>` |
| **Teams** (Vigie) | pas de dossier projet | dépôt d'un **enregistrement** (F-104 / SF-104-04) déjà prévu ; les autres fichiers vont dans `<racine>/.atelier/entrees/` du poste | selon le type |

**Règle** : on **dépose un fichier là où l'agent peut l'atteindre**, et le tour reçoit **le chemin**
dans le fil (« fichier déposé : `.atelier/entrees/capture.png` »). L'agent lit ensuite avec ses outils
existants. On ne pousse **pas** le contenu binaire dans la conversation du modèle (ni coût, ni limite
de contexte inutiles) — comme Claude Code, qui référence le fichier.

## 3. Ce que « tout type de fichier » veut dire ici

- **Aucun filtre de type au dépôt dans un terminal.** F-85 (liste des formats acceptés) concerne
  l'**upload documentaire** vers le fournisseur (OCR/RAG), pas le dépôt d'un fichier sur une machine ou
  dans un workspace : déposer un `.zip`, un `.log`, un `.mp4` sur **sa propre machine** ne regarde que
  l'utilisateur. Le message de refus de F-85 ne s'applique donc pas.
- **Bornes techniques** : taille par fichier (défaut **100 Mo** vers un poste par le runner en
  **session d'envoi découpée**, réutilise SF-108-06 ; **8 Mo** vers un workspace hébergé), nombre de
  fichiers par dépôt borné, nom de fichier assaini (pas de traversée de chemin), jamais hors de
  `entrees/`.
- **Un binaire n'est jamais réinjecté au modèle** : seul son chemin l'est. Un fichier **texte** peut,
  si l'agent le lit, entrer dans le tour — c'est son choix d'outil, pas le dépôt.

## 4. Ce que voit l'utilisateur

- **Glisser-déposer sur toute la zone du terminal** (projet, poste, Teams, et une tuile de mosaïque) :
  un voile « Déposer ici » apparaît au survol d'un fichier, dans la charte (aucune couleur nouvelle).
- **Le bouton trombone** dans le composer, en plus du glisser, pour choisir un fichier (parité avec
  SF-28-13, étendue aux terminaux runner).
- **Coller** une image ou un fichier depuis le presse-papiers (Cmd/Ctrl+V), comme dans Claude Code.
- **Progression** du dépôt (surtout pour un gros fichier vers un poste), **annulable** ; puis un bloc
  discret dans le fil : « fichier déposé : `<chemin>` — 2,3 Mo », que l'agent peut lire.
- **Échec nommé** : poste hors ligne, dossier non inscriptible, taille dépassée — jamais un silence.

## 5. Sécurité et cloisonnement

- Le dépôt suit l'**autorisation existante** : écrire sur la machine d'un poste passe par le
  `write_file` du runner (contrat F-38), donc par le poste appairé et possédé (`user_id` + `host_id`).
  Un dépôt **n'exécute rien** : écrire un fichier n'est pas lancer une commande, aucune porte de
  confirmation de commande n'est concernée ; mais le fichier reste **sous la racine**, jamais ailleurs.
- **Coupe-circuit** (SF-38-08) : un poste coupé refuse le dépôt.
- Isolation stricte : un dépôt ne peut viser que le workspace/poste du terminal ouvert.
- **Pas de secret réinjecté** : le contenu ne part pas au modèle sans une lecture explicite de l'agent.

## 6. Découpage

| SF | Titre | Contenu |
|---|---|---|
| SF-115-01 | Recevoir un dépôt côté serveur | Endpoint de dépôt d'un fichier vers un terminal, résolution de la cible (workspace hébergé → `entrees/` S3 ; poste → `.atelier/entrees/` par le runner en session découpée SF-108-06), bornes de taille et de nombre, nom assaini, isolation `user_id` + `host_id`, coupe-circuit ; le chemin déposé émis comme événement du tour |
| SF-115-02 | Glisser, coller, trombone dans le terminal | Zone de dépôt sur tout le terminal (projet, poste, Teams) et les tuiles de mosaïque, voile « Déposer ici », bouton trombone, collage presse-papiers, progression annulable, bloc « fichier déposé » dans le fil ; charte, aucune couleur nouvelle |
| SF-115-03 | L'agent sait qu'un fichier est là | La consigne du tour reçoit la liste des fichiers déposés depuis le dernier tour (chemins seulement) ; l'agent les lit avec `read_file` ; message d'échec nommé (hors ligne, non inscriptible, trop gros) |

**Ordre** : SF-115-01 → SF-115-02 → SF-115-03. SF-115-01 réutilise le transfert découpé vers le runner
de SF-108-06.

## 7. Préoccupations transversales

- **Navigation** : non. **Auth / tenant : oui** — dépôt filtré par poste/workspace possédé.
- **Plans / limites** : le dépôt suit le droit du terminal (Forge / Vigie) ; taille sur le quota de
  stockage pour un workspace hébergé, aucun quota de jetons consommé par le dépôt lui-même.
- **Composants** : `atelier-terminal.component` (projet, poste, Teams, mosaïque), composer,
  `WorkspaceService` / stockage S3 (hébergé), `RunnerToolGateway`/`write_file` et le transfert découpé
  (poste), `AtelierChatService` (consigne du tour), catalogue d'outils inchangé (`read_file` existe).

## 8. Hors périmètre

- Traiter le fichier (OCR, indexation RAG) : c'est le pipeline documentaire F-05+, pas le dépôt.
- Filtrer les types comme F-85 (qui vise l'upload vers le fournisseur, pas le dépôt sur une machine).
- Déposer ailleurs que sous `entrees/` ; déposer vers Microsoft 365 (c'est F-108).
