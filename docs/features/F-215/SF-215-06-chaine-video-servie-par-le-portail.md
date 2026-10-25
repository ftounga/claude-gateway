# Mini-spec — F-215 / SF-215-06 Chaîne vidéo servie par le portail

## Identifiant
`F-215 / SF-215-06` — Feature parente : F-215 « Le cours vidéo libre (court et long) » — Statut : `in-progress` — 2026-10-25
Branche : `feat/SF-215-06-chaine-video-portail`

## Objectif
Le poste prépare sa chaîne de rendu vidéo **sans rien télécharger sur internet** : Node 22, `hyperframes` 0.8.193, GSAP et ffmpeg/ffprobe arrivent en **un paquet signé servi par notre portail**, et le rendu utilise le **Chrome (ou Edge) déjà installé** sur le poste.

## Origine (incident du 2026-10-25)
Sur le poste du PO (CA-GIP, proxy d'entreprise, macOS), `video_render_check` a échoué : `RenderToolchain` téléchargeait Node 22.22.1 depuis `nodejs.org` et le proxy a répondu **HTTP 403**. Les étapes suivantes dépendaient aussi d'internet : `npm install` (registre npm), `hyperframes browser ensure` (Chrome), ffmpeg/ffprobe (gyan.dev, evermeet.cx, johnvansickle.com). Les tests de SF-215-02 **simulaient** ces téléchargements : personne ne l'a vu. Décision PO « correctif 2 » : tout vient du portail, que le runner joint déjà.

## Comportement attendu

### Cas nominal
1. **Fabrication (hors produit, machine qui a internet)** : `tools/video-toolchain/build-bundles.sh` fabrique un paquet `.tar.gz` par plateforme — `darwin-arm64`, `darwin-x64`, `linux-x64`, `win-x64` — contenant :
   - `node/` : Node **22.22.1** officiel (binaire seul + licence), archive amont vérifiée par empreinte épinglée ;
   - `hyperframes/node_modules/` : `hyperframes@0.8.193` + `gsap@3.13.0` installés **pour la plateforme cible** (`npm install --os --cpu [--libc] --ignore-scripts`), binaires natifs de la cible (esbuild, sharp/@img) ;
   - `ffmpeg/` : ffmpeg + ffprobe statiques (eugeneware/ffmpeg-static, tag `b6.1.1`, empreintes épinglées) avec leur licence ;
   - `bundle.json` (versions).
   Puis `manifest.json` (version du paquet, versions des outils, sha256 et taille de chaque paquet).
2. **Publication** : `tools/video-toolchain/upload-bundles.sh` **signe** le manifeste (Ed25519, la **clé de mise à jour du runner F-111**, lue dans AWS Secrets Manager, vérifiée contre la clé publique embarquée) et range paquets + manifeste + signature dans le bucket applicatif sous `toolchain/video/<version>/`. Lancé par l'exploitation au déploiement, jamais par un test.
3. **Gateway** — routes runner authentifiées par `X-Runner-Token` (même garde que les remontées runner, D9) :
   - `GET /runner/toolchain/video/{version}/manifest` → les octets exacts du manifeste (JSON) ;
   - `GET /runner/toolchain/video/{version}/signature` → la signature (texte Base64) ;
   - `GET /runner/toolchain/video/{version}/{platform}` → le paquet, **en flux** depuis le stockage, `Accept-Ranges: bytes`, `206` pour une plage (reprise), `416` hors fichier ; jamais le paquet entier en mémoire.
4. **Runner** — `RenderToolchain.prepare`, dans cet ordre :
   1. **paquet du portail déjà en place** (`<outils>/video-toolchain-<version>/`, marqueur de complétude) → l'utiliser ;
   2. sinon **Node ≥ 22 et hyperframes 0.8.193 + GSAP déjà présents** sur le poste (Node du PATH ou copie privée d'une version précédente ; installation `hyperframes-0.8.193` d'une version précédente) **et** ffmpeg/ffprobe présents sans téléchargement → les utiliser, rien n'est téléchargé ;
   3. sinon **le paquet du portail** : manifeste + signature lus par le client HTTP du runner (proxy d'entreprise, racine Zscaler) ; signature vérifiée avec la clé embarquée (F-111) ; paquet téléchargé dans `<outils>/video-toolchain-<version>-<plateforme>.tar.gz.part` avec **reprise par `Range`** ; taille et **sha256** comparés au manifeste ; extraction Java (garde de traversée, aucun lien) dans un dossier temporaire puis déplacement d'un bloc ; `node --version` vérifié.
   - **Plus jamais** `nodejs.org`, le registre npm, `browser ensure` ni un CDN.
   - **Navigateur** : détection Chrome/Edge existante (`ChromePaths`, surcharge `CLAUDE_TEAMS_CHROME_PATH`) → `HYPERFRAMES_BROWSER_PATH`.
   - **Environnement HyperFrames** (noms vérifiés dans le `dist` 0.8.193) : `HYPERFRAMES_NO_TELEMETRY=1`, `DO_NOT_TRACK=1`, `HYPERFRAMES_NO_UPDATE_CHECK=1`, `HYPERFRAMES_NO_AUTO_INSTALL=1`, `HYPERFRAMES_SKIP_SKILLS=1`, `HYPERFRAMES_NO_OPTIONAL_INSTALL=1`, `HYPERFRAMES_BROWSER_PATH`, `HYPERFRAMES_FFMPEG_PATH`, `HYPERFRAMES_FFPROBE_PATH`.
5. **F-190 (`video`)** : `LocalToolchain` consulte le paquet du portail pour ffmpeg/ffprobe **avant** gyan/evermeet/johnvansickle (déjà en place → utilisé ; sinon récupéré depuis le portail) ; la source internet ne reste qu'en **repli** si le portail ne sert pas le paquet.
6. **Messages** : avancement lisible (« je récupère la chaîne vidéo depuis le portail, ≈ N Mo, une seule fois », pourcentage visible par `video_render_check` pendant la préparation) ; succès « en place ». Description de `video_render` côté gateway alignée (plus de « téléchargé »).

### Cas d'erreur (résultat d'outil, cause précise + remède, jamais brew ni nodejs.org)
| Situation | Comportement |
|---|---|
| Jeton runner absent sur le poste | ÉCHEC « ce poste n'a pas de jeton runner » + remède « réappairer le poste » |
| Portail : 401 | ÉCHEC « le portail a refusé le jeton de ce poste » + remède « Réappairer ce poste… » |
| Portail : 404 (chaîne non publiée / plateforme absente) | ÉCHEC « le portail ne sert pas la chaîne vidéo <version> pour <plateforme> » + remède « prévenir l'administrateur du portail ; rien à faire sur le poste » |
| Portail injoignable (proxy 403, connexion refusée, délai) | ÉCHEC avec la cause réseau exacte ; la route est celle de la connexion du runner |
| Signature du manifeste invalide ou absente | ÉCHEC « signature invalide : rien n'est installé » + prévenir l'administrateur |
| Taille ou sha256 du paquet ≠ manifeste | `.part` supprimé ; ÉCHEC « paquet altéré ou tronqué en route » + relancer |
| Archive invalide (lien, traversée, fichier attendu absent) | ÉCHEC, rien laissé à moitié écrit |
| Plateforme sans paquet (linux-arm64, win-arm64…) | ÉCHEC nommé + remède « installer Node 22 et hyperframes 0.8.193 par le support » |
| Node du paquet refusé à l'exécution (AppLocker, noexec) | ÉCHEC « politique du poste, je ne la contourne pas » ; aucune autre tentative |
| Aucun Chrome ni Edge | ÉCHEC « aucun Chrome ni Edge trouvé (chemins cherchés) » + remède (portail logiciel de l'entreprise, ou `CLAUDE_TEAMS_CHROME_PATH`) |
| Route gateway : jeton absent/refusé | `401` générique `{"error": …}` |
| Route gateway : version mal formée, plateforme inconnue, objet absent | `404` `video_toolchain_unavailable` |
| Route gateway : plage hors fichier | `416` + `Content-Range: bytes */<taille>` |

## Critères d'acceptation
- [ ] Aucun accès à `nodejs.org`, au registre npm, à `browser ensure` ni à un CDN ne subsiste dans `RenderToolchain`.
- [ ] Les quatre paquets se fabriquent par le script versionné ; `file` sur `node`, `ffmpeg`, `ffprobe`, `esbuild` et le `.node` de sharp donne l'architecture de la cible (Mach-O arm64/x86_64, ELF x86-64, PE32+ x86-64).
- [ ] La gateway sert manifeste, signature et paquet au seul porteur d'un jeton runner valide ; `Range` → `206`, hors fichier → `416` ; le paquet passe en flux.
- [ ] Le runner vérifie signature + sha256 + taille avant d'extraire ; reprend un téléchargement interrompu ; un paquet altéré est refusé.
- [ ] **Preuve réelle** : paquet linux-x64 réellement fabriqué, servi en local ; `video_render_check` puis un rendu court produisent un **MP4** avec un runner **sans accès internet** (espace réseau isolé, proxy mort) et **sans Node 22 dans le PATH**.
- [ ] F-190 prend ffmpeg/ffprobe du paquet du portail avant toute source internet.
- [ ] Contrat runner↔gateway (chemins, en-tête, plateformes, version) vérifié dans `contract-tests/`.
- [ ] Le script d'upload existe et est documenté ; il n'est pas lancé.

## Contraintes de validation
| Champ | Obligatoire | Format / valeurs | Normalisation |
|---|---|---|---|
| `version` (chemin) | Oui | `[0-9A-Za-z][0-9A-Za-z.-]{0,39}` | aucune |
| `platform` (chemin) | Oui | `darwin-arm64`, `darwin-x64`, `linux-x64`, `win-x64` | aucune |
| `Range` | Non | une plage `bytes=` ; mal formée ⇒ réponse complète | — |
| `X-Runner-Token` | Oui | jeton runner actif | `strip()` |
| Manifeste (runner) | — | `kind = video-toolchain`, `version` = version épinglée, `hyperframes` = 0.8.193, entrée pour la plateforme, sha256 64 hex, taille > 0 et ≤ 1 Go | — |

## Technique
### Endpoints
| Méthode | URL | Auth | Rôle |
|---|---|---|---|
| GET | `/runner/toolchain/video/{version}/manifest` | `X-Runner-Token` | runner apparié |
| GET | `/runner/toolchain/video/{version}/signature` | `X-Runner-Token` | runner apparié |
| GET | `/runner/toolchain/video/{version}/{platform}` | `X-Runner-Token` | runner apparié |

### Composants
- Outils : `tools/video-toolchain/` (`versions.env`, `upstream.sha256`, `build-bundles.sh`, `SignToolchainManifest.java`, `upload-bundles.sh`, `README.md`).
- Gateway : `fr.claudegateway.runner.toolchain.VideoToolchainController` (+ service de flux sur `WorkspaceStorage`, clés `toolchain/video/<version>/…`) ; `RunnerSecurityConfig` (routes déclarées une par une) ; `VideoRenderToolCatalog` (description).
- Runner : `video.render.VideoToolchainBundle` (manifeste, signature, téléchargement repris, extraction), `video.render.TarGz` (lecteur tar.gz), `RenderToolchain` (ordre de résolution, navigateur, environnement), `RenderTools` (message de préparation), `teams.LocalToolchain` (source portail pour ffmpeg/ffprobe), `update.UpdateVerifier` (vérification de signature réutilisable), `ToolStack` (montage), `release-notes.txt`.
- Contrat : `contract-tests/.../VideoToolchainContractTest`.

### Tables / migration
Aucune table, **aucune migration**.

## Plan de test
### Unitaires (runner)
- `TarGzTest` : extraction ustar + chemin long (PAX/GNU), bit d'exécution, refus d'un lien et d'une traversée.
- `VideoToolchainBundleTest` (portail simulé par un vrai petit serveur HTTP local) : nominal (manifeste signé, sha256, extraction, marqueur) ; reprise `Range` d'un `.part` ; signature invalide ⇒ refus sans écriture ; sha256 faux ⇒ `.part` supprimé ; 401 / 404 / portail injoignable / pas de jeton ⇒ causes nommées ; plateforme sans paquet.
- `RenderToolsTest` réécrit : paquet en place ⇒ utilisé ; Node 22 + hyperframes déjà présents ⇒ rien téléchargé ; sinon portail ; aucun appel `npm`/`browser ensure`/`nodejs.org` ; navigateur du poste ⇒ `HYPERFRAMES_BROWSER_PATH` ; aucun navigateur ⇒ échec actionnable ; Node refusé ⇒ politique du poste ; environnement sans réseau (variables) ; message d'avancement pendant la préparation ; jamais « brew » ni « nodejs.org » dans un remède.
- `LocalToolchainTest` : ffmpeg du paquet du portail avant le téléchargement internet ; repli internet si le portail échoue.
### Intégration (gateway)
- `VideoToolchainControllerIT` (MockMvc, stockage en mémoire) : manifeste/signature/paquet avec jeton ⇒ 200 et octets exacts ; sans jeton / jeton refusé ⇒ 401 ; `Range` ⇒ 206 + `Content-Range` ; plage hors fichier ⇒ 416 ; plateforme inconnue, version mal formée, objet absent ⇒ 404.
### Contrat
- Chemins, en-tête, plateformes et version épinglée identiques des deux côtés.
### Preuve réelle (hors suite, rejouable)
- `build-bundles.sh linux-x64 darwin-arm64 win-x64` ; `file` sur les binaires natifs ; serveur local qui imite la route ; `VideoToolchainLiveTest` (activé par variable d'environnement) dans `unshare -rn` (réseau = boucle locale seule), `HTTPS_PROXY`/`HTTP_PROXY` vers un port mort, `NO_PROXY=localhost,127.0.0.1`, PATH sans Node : `video_render_check` puis `video_render` ⇒ MP4.
### Isolation
- Non applicable aux données utilisateur : le paquet est le même pour tous et ne porte aucune donnée ; l'accès est limité aux runners appariés (jeton). Aucun `user_id` lu ni écrit.

## Préoccupations transversales
- **Auth / Principal** : nouvelles routes runner sous la chaîne dédiée `/runner/**` (D9), jeton vérifié dans le contrôleur par `RunnerTokenAuthenticator`, rien dans le `SecurityContext`. Composants impactés : `RunnerSecurityConfig` (ajout de 6 entrées explicites, aucune autre modifiée), `VideoToolchainController`. Les autres routes runner sont inchangées (test de non-régression : suite complète).
- Contexte tenant, plans/limites, navigation : non concernés.
- **Politique du poste** : aucun contournement (exécution refusée ⇒ échec nommé) ; aucune levée de quarantaine macOS n'est tentée.

## Hors périmètre
Le déploiement et l'upload réel vers S3 (exploitation) ; la transcription locale F-190 (whisper, toujours GitHub/Hugging Face) ; Teams/F-90 (ffmpeg des captures, inchangé) ; plateformes ARM Linux/Windows ; mise à jour automatique d'un paquet déjà en place (une nouvelle version = nouveau dossier) ; écran.

## Dépendances
SF-215-02 et SF-215-03 (Terminées). F-111 (clé de signature du runner). Aucune question ouverte impactée.

## Notes et décisions (arbitrages réversibles, détaillés dans la PR)
- **Signature du manifeste avec la clé F-111** (pas une nouvelle clé) : le runner l'embarque déjà ; un proxy qui intercepte TLS ou une gateway compromise ne peuvent pas servir un autre paquet.
- **Stockage = `WorkspaceStorage` existant** (bucket applicatif `S3_BUCKET`), préfixe `toolchain/video/` ; rien dans l'image Docker.
- **ffmpeg statique eugeneware `b6.1.1`** : seule source qui couvre les quatre plateformes, ffprobe compris, avec un tag figé ; licence GPL jointe dans le paquet.
- **Extraction en Java** (pas `tar` du système) : identique sur les trois systèmes, garde de traversée, refus des liens.
- **Node seul** (sans npm) dans le paquet : plus aucun `npm install` sur le poste.
