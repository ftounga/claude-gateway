# Mini-spec — F-237 / SF-237-11 — Avant / après, comparaison de versions, planche contact

## Identifiant
`F-237 / SF-237-11` · Feature parente `F-237` · Statut `in-review` · 2026-11-02 · Branche `feat/SF-237-11-avant-apres-planche`
Cadrage : §7 (I5, I7), §14 idées 1 et 5. Dépend de SF-237-08 (actions `comparer`, `planche`, `portee.depuis`).

## Objectif
> « Ça marche maintenant ? » ⇒ la capture d'erreur et la preuve **côte à côte**, avec un rideau glissant ;
> « page v1 / v3 » ⇒ les deux versions ; « qu'est-ce qu'on a produit aujourd'hui ? » ⇒ la **planche contact** des
> productions du jour, en grille.

## Comportement attendu
- `afficher {action: comparer, pieces: [avant, après]}` ⇒ galerie en mode `compare` ; le panneau affiche
  `JarvisPieceCompareComponent` : deux **images** ⇒ rideau glissant (curseur 0-100 %, toucher / souris / clavier,
  cible ≥ 44 px) ; sinon deux visionneuses côte à côte (empilées au téléphone). Légende : description avant → après.
- **Versions** : l'index ne tient que la version courante d'une page / vidéo ; un identifiant `page:<id>@vN` /
  `video:<id>@vN` absent est **reconstitué** depuis la pièce courante (même id) : contenu par la passerelle
  (`/pages/{id}?version=N`, `/videos/{id}/versions/N/media`) — jamais une autre pièce.
- `afficher {action: planche, portee: {categorie: productions, depuis: AAAA-MM-JJ}}` ⇒ mode `contact` : grille (même
  sur PC), vignettes plus grandes, date et heure de chaque carte. Sans sujet : tous les sujets du compte (index borné).

### Cas d'erreur
- Une des deux pièces introuvable ⇒ la comparaison ne s'affiche pas (galerie normale) ; aucune pièce ⇒ « Je ne la
  trouve pas. ».
- Version inexistante côté passerelle ⇒ la visionneuse affiche son état d'échec existant (404 sans fuite).
- Présentation : refusé comme toute galerie.

## Critères d'acceptation
- [ ] Comparer deux images ⇒ rideau ; deux pages ⇒ deux visionneuses ; ordre avant / après respecté.
- [ ] Version antérieure lue par la passerelle, jamais confondue avec une autre pièce.
- [ ] Planche ⇒ grille, filtre productions + date transmis à l'index.

## Préoccupations transversales
Aucune cochée : écran seul ; lectures par les routes existantes (JWT, `requireOwned`).

## Plan de test
`jarvis-piece-compare.spec.ts` : `versionPiece`, `curtain`, disposition `contact`, magasin (comparer, versions,
planche), composant (rien sans les deux pièces, rideau présent).

## Hors périmètre
Comparaison vidéo synchronisée image par image ; partage de la planche (idée 10, plus tard).
