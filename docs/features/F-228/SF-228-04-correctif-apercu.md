# SF-228-04 — Correctif de l'aperçu des pièces jointes

**Feature :** F-228 · **Statut :** done (2026-10-31) · **Estimation :** < 1 j · **Branche :** `feat/SF-228-04-correctif-apercu` · **Dépend de :** SF-228-01/02/03 (mergées)

## Objectif
Ce que l'aperçu livré ne disait pas ou ne savait pas faire : dire la vraie erreur, télécharger une pièce lourde, lire le texte et le Word, décoder un CSV Excel, montrer les dates telles que saisies.

## Comportement
1. **Erreur lisible** : la réponse d'erreur d'une requête `blob` est elle-même un `Blob` ; `httpErrorMessage` ne la lisait pas (message générique). Nouveau `httpErrorMessageAsync` : lit le corps `Blob`, n'en extrait `message` que s'il ressemble à du JSON (jamais une page HTML).
2. **[Télécharger] au-delà de 20 Mo** : nouvelle route `GET /api/workspaces/{id}/deposits/{depositId}/download` — même isolation (pièce inscrite du compte et du terminal, 404 sinon), **lue en flux** (tranche par tranche depuis le poste, écrite au fil de l'eau), plafond **100 Mo** (= plafond de dépôt poste), toujours `application/octet-stream` + `Content-Disposition: attachment`, `nosniff`, `no-store`. La première tranche est lue AVANT d'écrire la réponse : poste hors ligne → 503 JSON lisible. Front : au-delà de 20 Mo, l'aperçu n'est pas demandé ; la boîte dit « trop lourde pour l'aperçu » et offre [Télécharger] (spinner pendant la lecture).
3. **Identifiant d'appel aléatoire** : `apercu-<uuid aléatoire>` / `telechargement-<uuid>` au lieu de `apercu-<depositId>` (deux aperçus simultanés de la même pièce — deux onglets — ne partagent plus d'identifiant d'appel côté runner).
4. **Aperçu texte** : `txt`, `md`, `log`, `json` en `<pre>` (interpolation, jamais de HTML), **200 Ko** au plus, coupe dite.
5. **docx** : `mammoth.extractRawText` (BSD-2, **chargé au clic seulement**), texte brut en `<pre>`, 200 Ko au plus. Jamais `convertToHtml` (aucun HTML inséré). Échec : « Ce document n'a pas pu être lu : téléchargez-le. »
6. **CSV** : décodé en UTF-8 strict ; s'il n'est pas de l'UTF-8 valide (CSV exporté par Excel FR), relu en **Windows-1252** (accents justes).
7. **Dates xlsx** : `read-excel-file` rend les dates en UTC ; elles étaient lues en heure locale (veille possible). Lecture en UTC, `jj/mm/aaaa`, heure ajoutée seulement si elle n'est pas minuit.

## Erreurs
| Situation | Comportement | HTTP |
|---|---|---|
| Pièce d'autrui / inconnue (téléchargement) | introuvable | 404 |
| Poste hors ligne au téléchargement | message de la passerelle (lu dans le Blob) | 503 |
| > 100 Mo | « Pièce trop lourde pour être téléchargée ici… » | 503 |
| Rupture du poste en cours de flux | connexion coupée (en-têtes déjà partis) ; le navigateur signale l'échec | — |

## Critères d'acceptation
- [x] Une erreur 503 en blob affiche le message de la passerelle.
- [x] `/download` : octets identiques, `attachment`, `octet-stream`, `nosniff` ; Bob → 404 ; pièce inconnue → 404.
- [x] Pièce > 20 Mo : pas d'appel à `/content`, [Télécharger] appelle `/download`.
- [x] Identifiant d'appel aléatoire (deux lectures → deux identifiants).
- [x] txt/json en `<pre>`, coupé à 200 Ko ; docx en texte brut via mammoth chargé au clic.
- [x] CSV Windows-1252 décodé juste ; UTF-8 inchangé.
- [x] Date xlsx en UTC (pas de veille), heure seulement si non nulle.

## Plan de test
- Back : `DepositContentApiIntegrationTest` (+ téléchargement, Bob 404, inconnue 404) ; `DepositContentServiceTest` (identifiant d'appel aléatoire, flux poste par tranches, plafond, première tranche en échec → exception avant écriture).
- Front : `deposit-preview-correctif.spec` (erreur Blob, > 20 Mo → download, texte coupé, CSV 1252, dates UTC, docx) ; `http-error.util.spec` (Blob JSON, Blob HTML).
- Isolation utilisateur : Bob → 404 sur `/download`.
- Suites complètes back et front, build.

## Impact
Back : `DepositContentService` (+`download`, identifiant aléatoire), `DepositContentController` (+`/download`), `ProjectFileRead` (+`stream`). Aucune migration. Front : `http-error.util` (+`httpErrorMessageAsync`), `deposit-preview.ts` (`text`/`docx`, `decodeCsv`, `cellText` UTC, `truncateText`, `depositDownloadUrl`), `deposit-preview-dialog.component`, `package.json` (+`mammoth@1.13.0`, publiée le 2026-09-26). Préoccupations transversales : aucune (ni auth, ni tenant, ni plans, ni routage).

## Arbitrages (gates 🟠 réversibles)
- **Téléchargement en flux par une route dédiée** plutôt que relever le plafond de `/content` : l'aperçu reste borné (mémoire passerelle), le téléchargement écrit tranche par tranche (jamais 100 Mo en mémoire côté passerelle). Côté navigateur, le fichier est reçu en `Blob` (le JWT ne voyage pas dans un lien `<a>`).
- **Plafond 100 Mo** = `app.atelier.deposit.max-runner-bytes` par défaut : aucune pièce déposée ne le dépasse.
- **mammoth `extractRawText`** (texte brut) plutôt que `convertToHtml` : aucun HTML à assainir.

## Hors périmètre
Mise en forme du Word (titres, tableaux) ; `.doc`/`.xls` anciens formats ; reprise d'un téléchargement interrompu.
