# F-246 — Les volets restent où on les met

**Demande PO du 2026-11-03** (premier vrai essai en prod des volets F-221 / F-226, sujet `data-platform`, chantiers 9, 10 et 11). Verbatim :

> « ça s'emmêlait les pinceaux » — « un coup le chantier 9 se retrouve à gauche, ensuite il se retrouve à droite, ensuite je me rends compte que je ne peux pas le rouvrir » — « je me perds dans les différents volets » — « je comprenais pas ce qui se passait ».

## Ce que le PO a vécu (prod, lecture seule, ses seules données)

`atelier_messages` et `subject_journey_events` du sujet `ac4b719f…`, 03/11 entre 18:28 et 19:12 (UTC) :

| Heure | Fait | Effet à l'écran (code F-221) |
|---|---|---|
| 18:41 | Dans le fil 9 : « choisis 2 tickets… fais le plan d'action » | — |
| 18:42:44 | `GUIDED_ACCEPTED` : le chantier 10 s'ouvre et **prend le focus** | le volet de gauche (= le focus) passe du 9 au 10 ; le 9 part à droite… s'il tourne encore, sinon il disparaît |
| 18:43:51 | `CHANTIER_FOCUSED chantier 9` (le PO le ramène) | la gauche repasse au 9, le 10 disparaît (son tour est fini) |
| 18:52:07 | chantier 11 ouvert, prend le focus | la gauche passe au 11 ; le 9 change encore de côté |
| 18:54:25 | « Go pour le chantier 10 » **écrit dans le fil 9** | le 10 n'avait plus de volet |
| 18:55:47 | « je ne vois pas le bouton valider » (fil 9) | le bouton « Valider » du plan du chantier 10 n'existe que dans le fil 10 — introuvable |

Le fil du chantier 10 contient 2 messages, celui du 11 en contient 14 ; le reste du travail sur 10 a été tapé dans le fil 9.

## Causes racines (code, `origin/main` 9e9866cd)

1. **Le volet de gauche suit le focus du serveur** (`AtelierComponent.chatLane` ← `focusChantierChange` du terminal). Le focus bouge à l'ouverture d'un chantier (`SubjectJourneyService.openAlongside` / acceptation : « le nouveau chantier prend le focus »), à « Mettre au focus », à « Basculer », à la fermeture du volet de gauche, à la clôture. Chaque bascule change le chantier de gauche — d'où « à gauche, puis à droite ».
2. **Le volet de droite est recalculé** (`splitFor`) à chaque changement de focus : « le plus ancien autre chantier ACTIF ». Un chantier dont le tour est fini n'est plus actif → son volet disparaît ; un troisième chantier actif prend la place.
3. **Aucun geste pour rouvrir un chantier dans un volet.** Après [Fermer ce volet], le chantier est mis dans `dismissedLanes` et ne revient « pas tant qu'il est actif » ; un chantier inactif n'a aucun moyen d'avoir un volet. Seul moyen : le mettre au focus, ce qui le met… à gauche et chasse l'autre.
4. **Rien n'est gardé** : au rechargement, la disposition est recalculée (focus + fils actifs). Un volet fermé revient s'il est actif.
5. **Aucun repère** : l'en-tête de gauche dit « Chantier 9 · +1 autre actif » (sans titre), celui de droite « Chantier 10 · titre » ; rien ne dit quels autres chantiers existent, ni où ils sont.
6. **Fils mélangés à l'écran** : quand un volet change de chantier, (a) le volet de droite garde les messages de l'ancien chantier tant que l'historique du nouveau n'est pas relu — et un envoi immédiat annule cette relecture ; (b) la bande du parcours, la barre d'état et le tableau d'un volet restent sur l'ancien chantier (le terminal ne relit son parcours qu'au changement de sujet).
7. **La reprise d'un chantier ouvert depuis un volet de droite** (« ✓ Nouveau chantier ouvert — investigation lancée. ») part dans le fil du volet d'où on l'a ouvert, pas dans celui du nouveau chantier.
8. **Le fil d'un chantier montre tout le passé commun** : `GET /chat?chantier=N` rend tout ce qui précède l'ouverture de N, quel qu'en soit le fil (c'est ce que le modèle relit, SF-221-01). Le volet du chantier 10, ouvert à 18:42, affichait d'abord les ~94 messages du chantier 9 : à l'œil, le fil du 9 « dans le volet du 10 ».

## Cible (la plus simple)

**La disposition appartient à l'écran.** Deux volets fixes ; chacun montre le chantier que l'utilisateur y a mis. Le focus du serveur ne déplace plus rien. Seuls changent la disposition :

- la **carte des volets** (nouvelle, une ligne au-dessus des volets dès que le sujet a 2 chantiers ouverts) : chaque chantier ouvert, son volet (« Gauche », « Droite » ou aucun), son état (« tourne », « à vous »), et deux boutons ◧ / ◨ pour le mettre à gauche ou à droite (déjà dans l'autre volet : les deux échangent) ;
- [Fermer ce volet] (le chantier continue ; il se rouvre depuis la carte) ;
- un chantier **ouvert** (« Ouvrir le chantier ») ou **lancé à côté** depuis un volet arrive dans **l'autre** volet ; sa reprise part dans **son** fil ;
- « Basculer sur le chantier N » / « Mettre au focus » / [Afficher ici] dans un volet : N vient dans **ce** volet.

Gardée par sujet sur l'appareil (`localStorage` `cg.atelier.panes.<sujet>`), relue au rechargement (chantiers clos écartés). Sans disposition gardée : le focus à gauche, le plus ancien autre chantier actif à droite (l'arrivée en split de F-221). Mettre un chantier à gauche le met aussi au focus côté serveur (Cap, Jarvis, autres écrans voient le même chantier principal) — mais le focus qui bouge côté serveur ne bouge rien à l'écran.

Le fil d'un chantier replie son passé commun derrière « Contexte d'avant le chantier N » (toujours relu par l'agent, inchangé côté serveur).

## Découpage

| SF | Objet | Estimation |
|---|---|---|
| SF-246-01 | Chaque chantier garde son volet : disposition à l'écran, carte des volets, fermer/rouvrir, ouverture et lancement dans l'autre volet, gardée au rechargement ; un volet qui change de chantier relit son parcours et efface l'ancien fil (causes 1-7) | 1,5 j |
| SF-246-02 | Le fil d'un chantier ne montre que lui : le passé commun replié derrière « Contexte d'avant le chantier N » (cause 8) | ½ j |

## Hors périmètre

Plus de deux volets ; volets redimensionnables ; disposition partagée entre appareils (serveur) ; changer la règle serveur « le nouveau chantier prend le focus » (elle sert le Cap et Jarvis ; l'écran ne la suit simplement plus).
