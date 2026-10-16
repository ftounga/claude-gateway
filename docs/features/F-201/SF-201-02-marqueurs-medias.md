# Mini-spec — [F-201 / SF-201-02] Marqueurs `[Image #N]` au curseur

## Identifiant
`F-201 / SF-201-02` — Feature parente `F-201` (Médias collés : noms uniques et marqueurs dans le texte) — Statut `in-review` — Créée 2026-10-16
Branche : `feat/SF-201-02-marqueurs-medias` — dépend de SF-201-01 (mergée, #1188 ; aucun contrat d'API modifié).

## Objectif
Comme Claude Code : un média collé, glissé ou choisi pose un marqueur `[Image #N]` (`[Vidéo #N]`, `[Fichier #N]`) à l'endroit du curseur ; le marqueur et la puce vivent ensemble ; à l'envoi, l'agent lit à cet endroit précis le chemin du fichier déposé.

## Comportement nominal
- **Position** : au collage / glisser / choix (trombone), le terminal émet `filesCaret` (sélection du champ) juste avant `filesSelected`. Au retour du dépôt, le parent attribue un marqueur par fichier déposé (type par extension du chemin rendu) et l'insère à cette position (`insertAtCaret` : espace ajouté seulement contre un mot). Un second dépôt se pose juste après le précédent.
- **Numérotation** : monotone par type dans le brouillon, remise à zéro à l'envoi (ou à la précision envoyée). Jamais réutilisée après un retrait.
- **Cycle de vie** : retirer la puce retire le marqueur du champ ; effacer le marqueur à la main retire la puce (et la pièce n'est plus jointe au message).
- **À l'envoi** : chaque marqueur encore présent devient `[Image #1 = .atelier/entrees/capture-….png]` à sa place dans le texte (`translateMarkers`) ; les `attachedDepositIds` (F-169) partent inchangés.
- **Texte collé (F-146)** : la référence `[texte collé #N]` se pose aussi au curseur (en remplaçant la sélection) ; numéro monotone (correctif du `pastes().length + 1` qui redonnait un numéro encore porté) ; effacer la référence retire sa puce.
- **Nom envoyé** : `depositFileName` renomme une capture générique (`image.png`, `image (2).png`, `blob`) en `capture-AAAAMMJJ-HHMMSS.<ext>` (le serveur reste la source de vérité, SF-201-01).
- La puce rappelle le marqueur (`.terminal-attachment__marker`, graisse seule, aucune couleur nouvelle).

## Cas d'erreur
| Situation | Comportement attendu |
|---|---|
| Dépôt refusé / annulé | puce d'échec sans marqueur, rien n'est inséré dans le champ |
| Champ absent ou sans sélection | marqueur posé en fin de brouillon |
| Curseur au-delà du texte (texte modifié pendant le dépôt) | position bornée à la longueur du brouillon |
| Marqueur effacé avant l'envoi | la pièce n'est ni traduite ni jointe |
| Ancienne puce sans marqueur | jointe comme avant (F-169), aucune traduction |

## Critères d'acceptation
- [ ] CA1 Coller une image au milieu d'une phrase pose `[Image #1]` à cet endroit ; on continue à taper autour.
- [ ] CA2 Deux images puis un PDF → `[Image #1] [Image #2] [Fichier #1]` ; un dépôt suivant → `[Image #3]`.
- [ ] CA3 Retirer la puce retire le marqueur ; effacer le marqueur retire la puce.
- [ ] CA4 À l'envoi, `[Image #1]` devient `[Image #1 = <chemin déposé>]` à sa place.
- [ ] CA5 `[texte collé #N]` est inséré au curseur ; après retrait de #1 parmi #1/#2, le collage suivant est #3.
- [ ] CA6 Une capture `image.png` part sous un nom `capture-…png`.
- [ ] CA7 Non-régression : `npm run build` vert ; `npm test` sans nouvel échec (base `origin/main` : 42 échecs de mesure de mise en page préexistants dans cet environnement, identiques avant/après).

## Plan de test
- `media-markers.spec.ts` (nouveau) : type par extension, numérotation, insertion au curseur (espaces, sélection, bornes), retrait, traduction, marqueurs disparus, `depositFileName`.
- `terminal-medias-colles.spec.ts` (nouveau) : `filesCaret` avant `filesSelected`, texte collé au curseur, correctif de numérotation, marqueur sur la puce, retrait puce→marqueur, effacement marqueur→puce (pièce et texte collé), traduction à l'envoi.
- `atelier.component.spec.ts` : marqueurs posés au curseur signalé, numérotés par type, second dépôt à la suite.
- Isolation utilisateur : sans objet (aucun nouvel accès données ; l'endpoint de dépôt reste isolé par SF-115/SF-201-01).

## Impacts
- Endpoints / tables : aucun.
- Composants : `AtelierTerminalComponent` (`filesCaret`, `removeAttachment`, `pruneVanishedMarkers`, collage au curseur, traduction à l'envoi), `AtelierComponent` (`onFilesCaret`, compteurs, insertion), `AtelierService.deposit` (nom envoyé), `TerminalDepositNotice.marker`, nouveaux `media-markers.ts` et `core/services/deposit-file-name.ts`, style `.terminal-attachment__marker`.
- Préoccupations transversales : aucune (ni auth, ni tenant, ni plans, ni routing).
- Préfixe système : inchangé — la traduction vit dans le message utilisateur.

## Arbitrages (gate 🟠 réversible — décidé par défaut)
- **Numérotation par type** (`[Image #1]` et `[Fichier #1]` coexistent), comme Claude Code compte les images. Alternative écartée : un compteur unique (`[Fichier #3]` pour un premier PDF, déroutant).
- **Forme traduite** `[Image #1 = chemin]` : le marqueur reste reconnaissable dans la bulle, le chemin est explicite pour l'agent. Le texte affiché dans la bulle est celui envoyé (comme l'expansion des textes collés F-146).
- **Marqueurs posés au retour du dépôt** (pas au collage) : un dépôt refusé ne laisse aucun marqueur orphelin ; la position est mémorisée au collage.
- **Effacer le marqueur retire la puce** sans appel serveur (dépôt « chemin uniquement », comme le retrait F-169).

## Hors périmètre
- Backend (SF-201-01, livrée).
- Aperçu miniature de l'image dans la puce ; glisser-déposer à une position précise du texte.
