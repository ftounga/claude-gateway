# Mini-spec — F-237 / SF-237-06 — Les visionneuses

## Identifiant
`F-237 / SF-237-06` · Feature parente `F-237` · Statut `in-review` · 2026-11-02 · Branche `feat/SF-237-06-visionneuses`
Cadrage : §8.2, §8.5 (son), §8.7, §12. Contrats : SF-237-01/02/03 (index, vignettes, courriel, schéma) ; vidéo
`GET /api/videos/{id}?version=` (ticket de lecture F-213) ; page `GET /api/pages/{id}` (ticket F-109).

## Objectif
> La pièce héros s'affiche **pour de vrai** — image, vidéo, audio, PDF, page, diapos, texte, fiche — et se pilote par
> le même état ; le son d'une vidéo baisse quand Jarvis parle et s'arrête quand le PO parle.

## Comportement attendu
- `JarvisPieceViewerComponent` choisit la visionneuse par `PIECE_VIEWERS` :
  - **image / schéma** : l'original (`open`) sous JWT, zoom ×1-×8 (molette, état), glisser = déplacer (zoomé),
    double-clic = ajuster ;
  - **vidéo / audio** : fiche de lecture ⇒ `mediaUrl` (ticket existant, `Range`) ; dépôt ⇒ l'original ; chapitres
    cliquables (aller à) ; l'état pilote lecture / pause / temps ; les commandes natives remontent `play` / `pause` ;
  - **PDF** : lecteur natif du navigateur sur l'URL d'objet, ouvert à la page de l'état (`#page=n`) ;
  - **page** : le cadre bac à sable existant (`app-page-frame`, ticket `/p/{token}/`) ;
  - **diapos** : la diapo n (`/presentations/{id}/slides/{n}`) ;
  - **texte** : besoin initial, envoi (description), courriel (corps **texte** seulement) ;
  - **fichier** : fiche (nom, taille, date), pas d'aperçu.
- **Son (D10, Q3 défaut)** : Jarvis parle ⇒ volume 15 % (rampe 200 ms), remonte 300 ms après ; le PO parle ⇒ pause,
  reprise après 1,5 s si aucun ordre n'est venu.
- Changement de pièce : chargement annulé (`AbortController`), URL d'objet révoquées.
- Poste hors ligne : « Sur le poste — hors ligne », rien n'est chargé.

### Cas d'erreur
- Contenu illisible (404, réseau) ⇒ « … illisible pour le moment », galerie intacte.

## Critères d'acceptation
- [ ] Image réelle zoomée par l'état ; vidéo lue par ticket, chapitre touché ⇒ état INSTANT.
- [ ] Courriel : corps texte ; page : cadre bac à sable ; fichier : fiche.
- [ ] Poste hors ligne : aucun chargement.
- [ ] Baisse du son à 15 %, pause à la parole du PO (fonctions pures testées).

## Préoccupations transversales
Aucune cochée : lectures par les routes déjà isolées (SF-237-01/02/03, vidéo, page).

## Plan de test
`jarvis-piece-viewer.spec.ts` : son (baisse, pause, rampe), adresses (fiche vidéo, diapo, PDF à la page), image
(original + transformation), vidéo (ticket, chapitres ⇒ état), page (cadre), courriel (texte), hors ligne (aucun
appel), fiche. Non-régression : suite front complète.

## Hors périmètre
Cibles nommées des schémas (SF-237-07) ; texture vidéo dans la scène 3D (voir arbitrage) ; T-G6 en 3D.

## Arbitrages (réversibles)
- **PDF par le lecteur natif** (`#page=n`) au lieu de `pdfjs-dist` (D12) : aucune dépendance ajoutée ; même
  navigation par page. Limite : certains navigateurs mobiles ne rendent pas un PDF en cadre (le PDF reste ouvrable).
  `pdfjs-dist` reste possible si l'usage le demande.
- Vidéo en élément DOM natif dans le panneau (PC comme téléphone), pas en texture three.js : cohérent avec la galerie
  en perspective CSS (SF-237-05), aucune mémoire GPU supplémentaire.
