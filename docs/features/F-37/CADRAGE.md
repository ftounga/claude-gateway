# Cadrage — F-37 : Voir les modifications

## Identifiant / Statut / Date

`F-37` · `cadré, décisions par défaut prises` · 2026-08-26

## Objectif

Montrer **ce qui a changé dans les fichiers** après un tour d'exécution, et pas seulement la liste des
fichiers touchés.

## Le problème

À la fin d'un run, l'écran affiche « Fichiers modifiés : `src/main/java/…/JwtService.java` ». C'est
tout. Pour savoir ce que l'agent a réellement écrit, il faut ouvrir le fichier dans l'explorateur et
le relire en entier — en se souvenant de ce qu'il contenait avant.

C'est le geste le plus fréquent avant d'accepter un travail, et c'est celui qui manque. Sur un dépôt
Git, l'utilisateur peut à la rigueur regarder la comparaison GitHub après publication ; sur un projet
importé par archive, il n'a rien.

## Ce dont nous disposons déjà

Le moment du calcul existe **sans rien ajouter** : lors de la resynchronisation, la Gateway tient
l'**ancien contenu** (lu depuis le stockage pour le remap de chemin) et le **nouveau** (téléchargé
depuis la session), juste avant d'écrire. Les deux versions sont en main au même instant.

Aucune bibliothèque de comparaison n'est présente dans le projet — le calcul sera écrit à la main, sur
un algorithme de plus longue sous-séquence commune, appliqué aux lignes.

## Décisions par défaut (à contredire si besoin)

| # | Décision | Pourquoi |
|---|----------|----------|
| D1 | Diff calculé **côté serveur**, au moment de la resynchronisation | C'est le seul instant où les deux versions coexistent ; le faire ailleurs demanderait de conserver l'ancienne |
| D2 | Format **unifié** avec quelques lignes de contexte | Format que tout développeur lit sans apprentissage |
| D3 | Borne **par fichier** configurable, défaut **400 lignes de diff** ; au-delà, mention du volume omis | Un fichier généré entièrement réécrit produirait des milliers de lignes que personne ne lit |
| D4 | Fichier **nouveau** → présenté comme un ajout intégral, borné de la même façon | Sans quoi la création d'un fichier n'apparaîtrait nulle part |
| D5 | Fichier **inchangé** (contenu identique) → **exclu** des modifications | Une session persistante réexpose ses sorties : sans cela, des fichiers intacts seraient annoncés comme modifiés |
| D6 | Diff **persisté avec le tour**, comme la transcription (SF-30-09) | Recharger la page ne doit pas faire perdre ce qu'on s'apprêtait à relire |
| D7 | Affichage **replié** par défaut, dépliable par fichier | Le diff complète le fil, il ne doit pas le noyer |

## Découpage

| SF | Contenu |
|----|---------|
| **SF-37-01** | Calcul du diff à la resynchronisation, borné ; relais dans le flux et persistance avec le tour (backend) |
| **SF-37-02** | Affichage dans la vue terminal : ajouts et retraits, repli par fichier (frontend) |

## Pièges identifiés

- **Ne pas confondre « réécrit » et « modifié ».** Une session persistante réexpose toutes ses sorties
  à chaque tour ; le registre incrémental (SF-30-09) filtre déjà les fichiers déjà rapatriés, mais un
  fichier réécrit à l'identique doit aussi être écarté — sinon le diff serait vide et l'annonce fausse.
- **Le coût mémoire** : comparer deux fichiers volumineux ligne à ligne est quadratique dans le pire
  cas. Borner **avant** de comparer, pas seulement à l'affichage.
- **Encodage** : les workspaces sont textuels (UTF-8) ; un contenu non décodable doit produire « fichier
  binaire ou illisible », pas une exception.

## Hors scope

Diff entre deux tours quelconques ; annulation d'une modification depuis le diff (ce serait F-38, le
retour en arrière) ; coloration syntaxique à l'intérieur du diff ; diff des fichiers supprimés par
l'agent.

## Effet attendu

Accepter ou refuser le travail de l'agent devient un geste de lecture, au même endroit que le reste —
au lieu d'un aller-retour vers l'explorateur, fichier par fichier, de mémoire.
