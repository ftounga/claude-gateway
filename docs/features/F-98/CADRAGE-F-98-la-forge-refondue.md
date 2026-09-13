# F-98 — La Forge, refondue

> Cadrage du 2026-09-13. **Maquette validée par le PO** (« J'aime beaucoup ta maquette… J'adhère à
> tes recos ») : `maquette-forge-refondue.html` dans ce dossier, publiée sur
> https://claude.ai/code/artifact/9b311a25-9513-4a7d-8b1f-5dd0bc452f77.
> **Cadrage seul : la livraison attend le go du PO.** Dépend de **F-97** pour le statut daté.

## 1. Le constat, dans ses mots

> « Je veux que l'écran soit refondu, il paraît très vertical ; avec le nombre de projets augmentant
> par poste, ce sera encore pire. Il faudra que tu réfléchisses à un design graphique joli, plus
> user-friendly. »

> « Y a-t-il une différence entre Voir travailler et Mosaïque ? »

> « C'est quoi Carte du poste ? — *La carte n'a pas été lue : lancez le runner sur la machine, puis
> Rafraîchir. Le terminal du poste s'ouvre à la racine, là où vit la carte : c'est de là qu'on
> l'écrit.* »

## 2. Pourquoi l'écran est vertical

`/forge` (`postes.component.html`, 771 lignes) empile **tous les postes**, et dans chaque poste
**onze blocs** les uns sous les autres : identité, faits, mission, activité, projets, dossiers non
ouverts, terminal du poste et son aperçu, terminal Teams, carte (résumé, fichiers, gain, constats,
indication), intégrité, actions. Chaque feature livrée depuis F-49 a **ajouté un bloc** à la carte,
et aucune n'avait mandat de revoir la page. La hauteur croît en **postes × (blocs + projets)**.

Trois défauts en découlent :

1. **L'échelle** : 4 postes font déjà plusieurs écrans ; 15 projets par poste la rendent illisible.
2. **La hiérarchie** : une autorisation qui attend est au même rang visuel qu'un gabarit de carte vide.
3. **Le vocabulaire** : des blocs écrits par des features successives parlent de leur mécanique
   (« la carte n'a pas été lue ») plutôt que de ce que l'utilisateur doit faire.

## 3. La forme retenue : une liste de postes, un poste ouvert

```
┌────────────────────────────────────────────────────────────────────────────┐
│ Forge   ● 2 postes en ligne sur 4   ● 1 autorisation attend   2/4 vivants │
│                                          [Voir travailler] [+ Connecter]   │
├──────────────────────┬─────────────────────────────────────────────────────┤
│ 🔍 Filtrer           │ [ED] EDENRED  ● En ligne  ~/dev  vu il y a 12 s      │
│ À REGARDER           │                  [Terminal du poste] [Teams] [···]  │
│ ▌ED EDENRED  1 attend│ Projets 4 │ Carte 12 faits │ Gouvernance ! │ Activité│
│ EN LIGNE             │ ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐      │
│  FR FREE          6  │ │ tuile   │ │ tuile   │ │ tuile   │ │ 3 non   │      │
│ HORS LIGNE           │ │ projet  │ │ projet  │ │ projet  │ │ ouverts │      │
│  CA CAGIP         1  │ └─────────┘ └─────────┘ └─────────┘ └─────────┘      │
│  RI Richemont     0  │                                                     │
│ CLÔTURÉS (replié)    │                                                     │
└──────────────────────┴─────────────────────────────────────────────────────┘
```

### Les cinq décisions (validées)

| # | Décision | Ce qu'elle règle |
|---|---|---|
| D1 | **Maître–détail** : colonne des postes à gauche (une ligne chacun), un seul poste ouvert à droite | La page ne grandit plus avec le nombre de clients |
| D2 | **Projets en grille de tuiles** (`auto-fill`, min ≈ 232 px) | 20 projets tiennent sur un écran de portable |
| D3 | **Onglets dans le poste** : Projets · Carte · Gouvernance · Activité, chacun portant un **résumé sans l'ouvrir** (« 12 faits », « à appliquer ») | Les onze blocs empilés disparaissent |
| D4 | **Ce qui attend passe devant** : groupes « À regarder / En ligne / Hors ligne / Clôturés » ; la tuile en attente porte la commande et le temps restant | L'urgence se voit du premier coup d'œil |
| D5 | **Le statut date** : « vu il y a 12 s » (livré par F-97 / SF-97-02) | Plus de « connecté » qui ment |

### Les règles de détail

- **Poste sélectionné dans l'URL** : `/forge/:hostRef` (et `/forge` ouvre le premier poste « À
  regarder », sinon le premier en ligne, sinon le premier). Le fil d'Ariane « chez qui » de F-68
  (`#poste-<id>`) **redirige** vers cette URL : aucun lien existant ne casse.
- **Onglet dans l'URL** : `?onglet=carte`, pour qu'un lien ou un retour arrière ramène au bon endroit.
- **Le filtre** cherche dans les noms de postes **et** de projets ; un poste qui ne correspond que par
  un projet reste listé, avec le nombre de projets trouvés.
- **Poste « Hébergé »** (F-71) : une ligne de la colonne comme les autres, sans point de statut ni
  onglet Carte / Gouvernance (il n'a pas de racine). Son détail n'a que Projets.
- **Postes clôturés** (F-60) : groupe replié en bas de colonne, comme aujourd'hui.
- **Tuile de projet** : nom, chemin en mono, badge « vivant » (§11), et **un seul** contenu central,
  par ordre de priorité : autorisation en attente (§12) › aperçu des dernières lignes › « au repos ·
  dernier tour … ». Pied : état de mission du projet, dette de promotion s'il y en a, « Ouvrir → ».
- **Dossiers non ouverts** : une tuile fantôme en fin de grille (« 3 dossiers non ouverts · Parcourir »)
  au lieu d'une liste.
- **Terminal du poste, Teams, menu** : dans l'en-tête du poste, à droite. Plus de bloc dédié.
  *Amendement F-106 (2026-09-13)* : le terminal Teams déménage dans la **Vigie** ; l'en-tête de la
  Forge garde « Ouvrir dans la Vigie » quand le client y est activé. Aucun onglet Radar dans la Forge.
- **Téléphone (< 820 px)** : la colonne devient une liste plein écran ; toucher un poste ouvre son
  détail, avec un retour. Même URL.

## 4. Voir travailler et Mosaïque : un seul bouton, deux densités

Ce sont aujourd'hui **deux routes** et **deux boutons** pour le même besoin, regarder travailler ses
terminaux :

| | `/forge/supervision` (F-76) | `/forge/mosaique` (F-83) |
|---|---|---|
| Ce qu'on voit | des **aperçus** : dernières lignes et activité | les **quatre flux entiers**, vivants, lecture seule |
| Coût | aucun flux, aucune place | une place de lecteur par tuile |

**Décision** : un seul bouton **« Voir travailler »** dans la barre de la Forge. En haut de l'écran,
un sélecteur **Aperçus / Flux entiers**, retenu par utilisateur (`localStorage`, repli « Aperçus »).
Une seule route `/forge/voir` portant `?densite=apercus|flux`. **Les deux anciennes routes
redirigent** vers la bonne densité, les liens existants ne cassent pas.

## 5. Les textes réécrits

Règle : **dire ce qu'on voit et ce qu'on peut faire**, jamais la mécanique.

| Aujourd'hui | Demain |
|---|---|
| « La carte n'a pas été lue : lancez le runner sur la machine, puis Rafraîchir. » | **« Poste hors ligne : la carte sera lue à la prochaine connexion. »** (et plus rien à cliquer : la relecture est automatique au retour en ligne, voir ci-dessous) |
| « Le terminal du poste s'ouvre à la racine, là où vit la carte : c'est de là qu'on l'écrit. » | En tête de l'onglet Carte : **« Ce que vous savez de l'infrastructure de ce client. Chaque projet l'enrichit. »** Et le bouton « Écrire dans la carte » ouvre le terminal du poste |
| « Connecté » / « Déconnecté » | « En ligne · vu il y a 12 s » / « Hors ligne · vu il y a 18 min » / « Jamais connecté » |

**Relecture de la carte au retour en ligne** : aujourd'hui la carte n'est lue qu'au chargement ou sur
« Rafraîchir » (règle A1 de F-72 / SF-72-03 : jamais par le sondage). On garde la règle, et on ajoute
**un seul** déclencheur : le passage hors ligne → en ligne d'un poste relit sa carte **une fois**.
Pas de sondage de la machine ajouté.

## 6. Le découpage

| SF | Titre | Contenu |
|---|---|---|
| SF-98-01 | La colonne des postes | Composant de colonne (identité §9, statut daté, groupes par attention, filtre postes + projets, groupe clôturés replié), route `/forge/:hostRef`, redirection de `#poste-<id>`, bandeau de synthèse de la flotte |
| SF-98-02 | Le poste ouvert et ses onglets | En-tête (identité, statut, racine, système, actions Terminal / Teams / menu), onglets avec leurs résumés, `?onglet=` ; les blocs existants **déplacés** dans les onglets Carte, Gouvernance (bandeau F-96 et intégrité F-95), Activité, sans réécrire leur logique |
| SF-98-03 | Les projets en grille | Tuile de projet (règle de priorité du contenu central), tuile fantôme des dossiers non ouverts, tri « Actifs d'abord / A→Z / Récents », bouton « Ajouter un projet » |
| SF-98-04 | Voir travailler, un seul écran | Route `/forge/voir?densite=`, sélecteur retenu, redirection des deux anciennes routes, un seul bouton dans la Forge |
| SF-98-05 | Les textes et la carte au retour en ligne | Réécriture des libellés (§5), relecture unique de la carte au passage en ligne, écran téléphone (colonne → liste → détail) |

**Ordre** : F-97 d'abord, puis SF-98-01 → 02 → 03 (chacune laisse l'écran utilisable), puis 04 et 05
en parallèle.

## 7. Le design system

`DESIGN_SYSTEM.md` gagne un **§16 — La Forge : colonne et détail** : largeur de colonne (290 px),
ligne de poste (pastille d'initiales 34 px teintée §9, filet de sélection de la couleur du poste),
tuile de projet (bordure `#E2E8F0`, rayon 9 px, surbrillance ambre §12 pour l'attente), onglets (filet
orange `#E07B39` sous l'onglet actif), tuile fantôme (pointillés). **Aucune couleur hors charte**
ajoutée : tout vient de §2, §9, §10, §11, §12.

## 8. Hors périmètre

- Toute évolution **fonctionnelle** des blocs déplacés (carte, gouvernance, intégrité, Teams) : on
  les range, on ne les change pas.
- Le glisser-déposer de postes ou de projets, les vues enregistrées.
- Plus de quatre flux dans « Voir travailler » (plafond de F-70 inchangé).

## 9. Préoccupations transversales

- **Navigation / routing : oui.** Composants impactés : `app.routes.ts` (`forge`, `forge/supervision`,
  `forge/mosaique`, nouvelles `forge/:hostRef` et `forge/voir`), fil d'Ariane F-68 (fragment
  `#poste-<id>`), liens vers la Forge depuis le terminal d'un projet, le dialogue d'appairage (retour
  après connexion), le bandeau de gouvernance F-96, les e-mails ou notifications qui pointent la
  Forge. Test de non-régression par ancien chemin.
- **Auth / Principal, tenant, plans / limites** : non. Les endpoints lus sont inchangés.

## 10. Critère d'acceptation de la feature

Avec **4 postes et 15 projets sur l'un d'eux**, sur un écran de portable 1440 × 900 : la liste des
postes et au moins 12 tuiles de projet sont visibles **sans faire défiler la page** ; une autorisation
en attente sur n'importe quel poste est visible **sans clic** (bandeau de synthèse + groupe « À
regarder ») ; tout lien existant vers `/forge`, `/forge/supervision`, `/forge/mosaique` ou
`#poste-<id>` mène au bon endroit.
