# Mini-spec — F-147 / SF-147-06 — Rattraper un texte resté sur le poste

## Identifiant
`F-147 / SF-147-06` — feature parente `F-147`

## Objectif
Qu'une réunion dont le texte n'a pas pu remonter — poste décroché, runner redémarré — **finisse par
l'obtenir**, sans réveiller la boîte aux lettres que le PO a refusée.

## Le défaut
SF-147-02 fait remonter le texte au terme de la transcription, sur un fil du runner. Trois façons de
perdre ce fil :

1. la **gateway est injoignable** au moment du dépôt du texte ;
2. le **runner redémarre** pendant la transcription — les travaux vivent en mémoire ;
3. la transcription **n'avait pas commencé** (moteur absent à ce moment-là).

Dans les trois cas, le fichier est **là**, sur la machine, avec son compagnon ; la réunion existe,
« en attente ». Personne ne revient jamais la remplir.

## Ce qu'on ne fait pas
**On ne remet pas la surveillance du dossier.** Le refus du PO est clair : *« je ne veux pas de
mécanisme où derrière on va positionner des fichiers… on ne sait pas quand le runner va venir
prendre »*. Ce qui est rattrapé ici, ce sont **uniquement les dépôts venus de l'écran**, reconnaissables
à leur compagnon : ils portent l'identifiant de la réunion qui les attend. Un fichier posé à la main
dans le dossier n'est **pas** pris — et ne le sera plus après SF-147-05.

## Comportement attendu
1. Le compagnon d'un dépôt venu de l'écran porte **l'identifiant de la réunion** et, une fois le texte
   remonté, une **marque** disant que c'est fait.
2. **Au démarrage du runner**, les dépôts qui portent une réunion **sans marque** sont repris : on
   transcrit si besoin, puis on poste le texte.
3. Un dépôt **déjà remonté** n'est jamais repris — la marque est la seule chose qui le dit.
4. La reprise est **bornée** : quelques dépôts par démarrage, pas tout le dossier.
5. Une reprise qui échoue laisse tout en place : elle **réessaiera** au démarrage suivant.

| Cas d'erreur | Comportement |
|---|---|
| Compagnon illisible ou sans réunion | ignoré, sans bruit : ce n'est pas un dépôt venu de l'écran |
| Fichier disparu, compagnon resté | ignoré ; rien à transcrire |
| Gateway toujours injoignable | rien n'est marqué : le prochain démarrage réessaiera |
| Moteur de transcription absent | rien n'est marqué ; le dépôt attend un poste équipé |

## Critères d'acceptation
- [x] Le compagnon d'un dépôt venu de l'écran porte l'identifiant de la réunion.
- [x] Une remontée réussie **marque** le compagnon.
- [x] Au démarrage, un dépôt **porteur d'une réunion et sans marque** est repris.
- [x] Un dépôt **marqué** n'est pas repris.
- [x] Un dépôt **sans réunion** (posé à la main) n'est **jamais** pris.
- [x] La reprise est bornée et ne bloque pas le démarrage.

## Hors scope
Le retrait de la surveillance périodique (**SF-147-05**, qui suit immédiatement) · toute reprise
**périodique** : ici, c'est **au démarrage**, un moment où l'on sait que le poste est vivant.

## Technique
| Élément | Changement |
|---|---|
| `RadarDepositReceiver` | le compagnon gagne `meeting_id` ; la remontée réussie écrit `transcript_sent` |
| `RadarDepositResume` *(nouveau)* | la reprise au démarrage : lit les compagnons, reprend les dépôts sans marque |
| `TeamsTools` / `ToolStack` | la reprise est lancée une fois, au montage, sur un fil démon |

Aucune table, aucune migration, aucune route.

**Bornes** : `MAX_RESUMED = 3` dépôts par démarrage — au-delà, ils attendront le suivant ; la reprise
ne doit jamais retarder un poste qui démarre.

## Plan de test
### Runner — `RadarDepositResumeTest`, 6 verts
- [x] Un dépôt porteur d'une réunion **sans marque** est repris, remonté, puis **marqué**.
- [x] Un dépôt **déjà remonté** n'est pas repris : la réunion ne reçoit pas deux fois le même texte.
- [x] **LA BOÎTE AUX LETTRES NE REVIENT PAS** : un fichier posé à la main (compagnon sans réunion, ou
      pas de compagnon du tout) n'est **jamais** pris.
- [x] Une remontée refusée ne marque rien : le prochain démarrage réessaiera, le fichier reste en place.
- [x] La reprise s'arrête à `MAX_RESUMED` ; sans moteur ni remontée, rien n'est tenté.

## Préoccupations transversales
| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| Auth / Principal | non | la remontée réutilise la route et le jeton de SF-147-02, inchangés |
| **Contexte tenant** | **oui** | rien de nouveau : le couple compte/poste vient **du jeton runner**, comme en SF-147-02 ; la réunion visée est celle inscrite dans le compagnon **par la gateway**, jamais devinée. |
| Plans / limites | non | transcription locale, aucun appel fournisseur |
| Navigation / routing | non | aucun écran |
