# Chaîne vidéo servie par le portail (F-215 / SF-215-06)

Le poste ne télécharge plus rien sur internet pour faire une vidéo : Node 22, `hyperframes`, GSAP et ffmpeg/ffprobe
arrivent en **un paquet signé par plateforme**, servi par la gateway que le runner joint déjà
(`GET /runner/toolchain/video/<version>/{manifest,signature,<plateforme>}`, jeton runner). Le rendu utilise le
Chrome ou l'Edge du poste.

| Fichier | Rôle |
|---|---|
| `versions.env` | versions épinglées ; `TOOLCHAIN_VERSION` = `VideoToolchainBundle.VERSION` (runner) |
| `upstream.sha256` | empreintes épinglées des archives amont (Node officiel, ffmpeg-static) |
| `npm/package.json`, `npm/package-lock.json` | hyperframes + gsap verrouillés (intégrité npm) |
| `build-bundles.sh` | fabrique `out/<version>/<plateforme>.tar.gz` + `manifest.json` (machine Linux avec internet) |
| `SignToolchainManifest.java` | signe le manifeste (Ed25519, clé F-111 du runner) |
| `upload-bundles.sh` | signe puis publie dans `s3://<bucket>/toolchain/video/<version>/` |
| `serve-local.py`, `prove-offline.sh` | essais seulement : portail local et preuve hors ligne |

## Publier une version (au déploiement)

```bash
tools/video-toolchain/build-bundles.sh                   # les 4 plateformes, ~500 Mo dans out/
tools/video-toolchain/upload-bundles.sh --dry-run        # vérifie, signe, montre les copies
tools/video-toolchain/upload-bundles.sh                  # publie (refuse d'écraser une version publiée sans --force)
```

`upload-bundles.sh` lit la clé privée dans AWS Secrets Manager (`claude-gateway/runner-update-signing-key`, la
même que `deploy2.sh`), la garde dans un dossier temporaire `0700` supprimé à la sortie, et revérifie la signature
avec `runner/src/main/resources/update-signing-public-key.pem` avant d'envoyer quoi que ce soit. Le manifeste est
envoyé **en dernier**. Variables : `BUCKET`, `AWS_REGION`, `SIGNING_SECRET_ID`.

## Changer une version

Modifier `versions.env` (incrémenter `TOOLCHAIN_VERSION` : `-r2`…), `upstream.sha256`, `npm/` (`npm install
--package-lock-only` dans `npm/`), et `VideoToolchainBundle.VERSION` dans le runner (le contrat le vérifie). Une
nouvelle version s'installe dans un nouveau dossier du poste ; l'ancienne n'est jamais modifiée.

## Prouver hors ligne

```bash
tools/video-toolchain/build-bundles.sh linux-x64
tools/video-toolchain/prove-offline.sh
```

Espace réseau isolé (`unshare -rn`), proxy mort, PATH sans Node 22 : le vrai montage du runner récupère la chaîne
au portail local, lance `video_render_check` puis un rendu de 3 s, et vérifie le MP4 (h264, 1280×720).
