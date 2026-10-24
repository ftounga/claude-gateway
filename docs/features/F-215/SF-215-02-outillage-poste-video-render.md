# Mini-spec — F-215 / SF-215-02 Outillage du poste : capacité runner `video_render`

## Identifiant
`F-215 / SF-215-02` — Feature parente : F-215 « Le cours vidéo libre (court et long) » — Statut : `in-review` — 2026-10-25
Branche : `feat/SF-215-02-video-render`

## Objectif
Le poste sait **rendre une composition HyperFrames en mp4** : un runner à jour annonce la capacité `video_render`, prépare tout seul sa chaîne (Node 22, `hyperframes` **0.8.193** épinglé, Chrome sans écran, ffmpeg/ffprobe) et lance un rendu long **détaché** dont l'agent suit l'avancement — avec un échec clair et actionnable sur un poste verrouillé, sans jamais contourner sa politique.

## Comportement attendu
### Cas nominal
- **Capacité** `video_render` annoncée dans la trame `ready` **seulement si** les outils de rendu sont montés **et** l'exécution de commandes est autorisée sur ce poste (`bash` actif) — un poste qui interdit les commandes n'en lance pas par ce biais.
- **Gateway** : `video_render` et `video_render_status` déclarés **seulement** sur un poste qui annonce la capacité (patron F-190 `video`), jamais sur un projet hébergé ; relais `RunnerToolGateway.video` ; capacité exigée par `RunnerCallDispatcher` (le préfixe `video_render` passe avant `video_`) ; `video_render` passe la **même porte que `bash`** (accord si le sujet demande avant les commandes, règles de permission par outil respectées) ; `video_render_status` est un suivi sans porte.
- **`video_render`** (rend la main tout de suite avec un `job_id`) :
  - `dossier` (dossier du projet HyperFrames, relatif au projet ; requis sauf `verifier_seulement`), `composition?` (relatif au dossier, défaut `index.html`), `sortie?` (mp4 relatif au projet ; défaut `<dossier>/renders/<nom du dossier>.mp4`), `qualite?` (`draft` | `looks` | `delivery`, défaut `looks`), `fps?` (24, 25, 30, 60), `workers?` (1–8), `verifier_seulement?` (prépare la chaîne et la décrit, sans rendre).
  - **Phase « Préparation »** (une seule fois par poste, ensuite instantanée) :
    1. **Node ≥ 22** : celui du `PATH` s'il est en 22+ ; sinon une copie déjà rapatriée ; sinon rapatriement de **Node 22.22.1** (archive officielle `nodejs.org`, adresse en dur, HTTPS, client réseau du runner — proxy/Zscaler) dans `.claude-runner/teams/tools/node-v22.22.1/`, puis vérification `node --version`.
    2. **hyperframes@0.8.193** : `npm install --prefix .claude-runner/teams/tools/hyperframes-0.8.193 hyperframes@0.8.193` avec le npm de ce Node (registre et `.npmrc` du poste respectés), vérifié par la version du `package.json` installé.
    3. **Chrome sans écran** : `hyperframes browser ensure` (Chrome épinglé par HyperFrames) ; à défaut, un Chrome installé sur le poste (`HYPERFRAMES_BROWSER_PATH`).
    4. **ffmpeg / ffprobe** : ceux du poste, sinon rapatriés au premier usage (chaîne F-90/F-190).
  - **Phase « Rendu »** : `hyperframes render <dossier> -o <sortie> --quality … [-c …] [--fps …] [--workers …]` lancé **détaché** (`HYPERFRAMES_RENDER_DETACHED=1`, groupe de processus propre via `setsid` quand il existe, sortie dans `render.log`, aucun tube vers le runner) : il survit à la fin du tour et à un redémarrage du runner. Télémétrie et vérification de mise à jour HyperFrames coupées (`HYPERFRAMES_NO_TELEMETRY=1`, `HYPERFRAMES_NO_UPDATE_CHECK=1`).
  - Un rendu déjà en cours pour la même sortie est **rendu tel quel** (pas de doublon).
- **`video_render_status`** (`job_id`) : étape, avancement (dernier pourcentage ou `frame n/m` du journal), temps écoulé, dernières lignes du journal ; à la fin : chemin et taille du mp4 (à publier ensuite par `video_publier`) ; en échec : la cause **et le remède**, à répéter tels quels. Un `job_id` d'un autre projet est inconnu (le travail vit sous `.atelier/rendu/<job_id>/` du projet).

### Cas d'erreur (tous en résultat d'outil, jamais une exception nue)
| Situation | Comportement |
|---|---|
| runner ancien (pas la capacité) | outils non déclarés ; appel égaré refusé localement (« mettez à jour le poste ») |
| `bash` interdit sur le poste | capacité non annoncée, outils non déclarés |
| `dossier` absent ou introuvable, composition absente ou hors du dossier | `invalid_input` / `not_found` explicites |
| `qualite`, `fps`, `workers` hors liste | `invalid_input` |
| Node absent et rapatriement impossible (réseau, proxy, pas d'archive pour ce système) | ÉCHEC « Node 22 introuvable sur ce poste et son rapatriement a échoué … » + remède (commande d'installation par système, ou support) |
| Node rapatrié mais exécution refusée (politique du poste, AppLocker, noexec) | ÉCHEC « exécution refusée par la politique du poste — je ne la contourne pas » + remède (installation par le support) ; **aucune autre tentative** |
| `npm install hyperframes@0.8.193` refusé (registre bloqué, 403, proxy) | ÉCHEC + fin du journal npm + remède (accès au registre ou installation par le support) |
| Chrome indisponible (téléchargement bloqué, aucun Chrome installé) | ÉCHEC + remède |
| ffmpeg introuvable et non rapatriable | ÉCHEC + remède (commande d'installation) |
| rendu en échec (« Render failed » / code ≠ 0 sans mp4) | ÉCHEC + dernières lignes du journal |
| `job_id` inconnu | `not_found` « relance video_render » |

## Critères d'acceptation
- [ ] La capacité `video_render` est annoncée si et seulement si les outils sont montés et `bash` autorisé.
- [ ] Gateway : outils déclarés seulement avec la capacité, jamais en hébergé ; relayés au runner ; `video_render` passe la porte de `bash` ; contrat : noms et capacité identiques des deux côtés (`contract-tests`).
- [ ] Chaîne prête : Node du PATH ≥ 22 réutilisé ; Node < 22 ou absent ⇒ rapatriement de la version épinglée ; hyperframes installé à la version **0.8.193** exactement ; Chrome via `browser ensure` ou Chrome du poste ; ffmpeg/ffprobe résolus.
- [ ] Rendu détaché (`HYPERFRAMES_RENDER_DETACHED=1`, journal fichier), suivi interrogeable (étape, %, fin, taille) ; même sortie en cours ⇒ même travail.
- [ ] Poste verrouillé : chaque échec dit **quoi** et **quoi faire**, et n'essaie aucun contournement.

## Contraintes de validation
| Champ | Obligatoire | Format |
|---|---|---|
| dossier | Oui (sauf `verifier_seulement`) | chemin résolu comme tout chemin du poste (`PathResolver`, modèle « poste sans confinement ») |
| composition | Non | fichier existant sous `dossier`, défaut `index.html` |
| sortie | Non | chemin résolu par `PathResolver`, extension `.mp4` imposée |
| qualite | Non | `draft`, `looks`, `delivery` |
| fps | Non | 24, 25, 30, 60 |
| workers | Non | entier 1–8 |
| job_id | Oui (status) | 12 hex |

## Technique
- Runner : paquet `fr.claudegateway.runner.video.render` (`RenderTools`, `RenderToolchain`, `RenderJob`, `RenderJobs`, `RenderProcesses`, `RenderFailure`) ; `ToolRouter` (aiguillage + capacité), `ProjectScopes`/`ToolStack` (montage), `LocalTool.ffprobe()` ; `release-notes.txt`.
- Gateway : `atelier/video/VideoRenderToolCatalog` ; `AtelierChatService` (déclaration, relais, étape, porte) ; `RunnerCallDispatcher.capabilityFor` ; `RunnerToolGateway.video`.
- Contrat : `contract-tests/.../VideoRenderToolsContractTest`.
- Aucune table, aucune route HTTP, aucune migration.

## Plan de test
- Runner (unitaires, processus et téléchargements simulés) : `RenderToolsTest` — capacité selon `bash` ; préparation nominale (Node du PATH 22, hyperframes installé à 0.8.193, browser ensure, ffmpeg) puis rendu détaché ⇒ statut TERMINÉ avec taille ; Node 18 ⇒ rapatriement ; rapatriement en échec ⇒ ÉCHEC + remède ; Node rapatrié non exécutable ⇒ message « politique du poste », aucune autre tentative ; npm refusé ⇒ ÉCHEC + remède ; browser ensure en échec sans Chrome ⇒ ÉCHEC ; dossier introuvable / composition absente ou hors du dossier / qualité, fps, workers invalides ⇒ refus ; même sortie en cours ⇒ même `job_id` ; `job_id` inconnu ; avancement lu dans le journal ; `ToolRouterTest` (aiguillage).
- Gateway : `AtelierChatServiceVideoRenderToolsTest` — déclaration selon la capacité, jamais en hébergé, relais au runner, porte de `bash` quand le sujet demande avant les commandes ; capacité exigée (`RunnerCallDispatcher`).
- Contrat : noms partagés.
- Suite complète backend + suite runner avant merge.

## Préoccupations transversales
- **Contexte tenant** : le travail vit sous le projet du tour (`PathResolver`) ; la gateway n'ajoute aucune donnée. Composants : `RenderTools`, `AtelierChatService`.
- **Plans / limites** : aucun (rendu local, gratuit côté gateway).
- Auth / navigation : non.
- **Politique du poste** (règle « porte de validation vs policy client ») : aucun contournement — échec nommé + remède ; capacité liée à l'autorisation `bash`.

## Hors périmètre
Savoir-faire « cours vidéo », briques graphiques, relecture `lint`/`snapshot` (SF-215-03) ; série d'épisodes (SF-215-04) ; publication (déjà `video_publier`) ; rendu Docker/cloud ; projet hébergé.

## Notes et décisions (arbitrages réversibles)
- **Node épinglé 22.22.1** rapatrié dans le dossier d'outils du runner (pas d'installation système, pas de droits administrateur) ; un Node 22+ du poste est toujours préféré.
- **Groupe propre** : `setsid` quand il est présent (Linux) ; ailleurs, le mode détaché d'HyperFrames (ignore SIGHUP, ne surveille plus ses parents) + journal fichier suffisent à survivre au tour ; sous Windows les processus enfants survivent au parent.
- **Télémétrie HyperFrames coupée** sur le poste client (aucune donnée ne part chez HeyGen).
- `video_render` passe **la porte de `bash`** : il lance des programmes sur le poste.
