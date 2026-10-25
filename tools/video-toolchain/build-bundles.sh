#!/usr/bin/env bash
# F-215 / SF-215-06 — Fabrique les paquets de la chaîne vidéo servis par le portail.
#
# Usage : tools/video-toolchain/build-bundles.sh [plateforme…]
#   plateformes : darwin-arm64 darwin-x64 linux-x64 win-x64 (défaut : les quatre)
#
# À lancer sur une machine Linux qui a internet (GNU tar, gzip, unzip, xz, sha256sum, file, curl, npm ≥ 10).
# Rien n'est envoyé nulle part : les paquets et le manifeste sont écrits dans
#   tools/video-toolchain/out/<TOOLCHAIN_VERSION>/
# La publication (signature + S3) est le travail de upload-bundles.sh.
#
# Chaque paquet contient :
#   node/bin/node (node/node.exe sous Windows) + node/LICENSE      Node officiel, archive vérifiée (upstream.sha256)
#   hyperframes/node_modules/…                                       hyperframes + gsap, `npm ci` pour la CIBLE
#                                                                    (--os/--cpu/--libc, --ignore-scripts), lockfile versionné
#   ffmpeg/ffmpeg, ffmpeg/ffprobe (+ .exe) + LICENSE                 statiques eugeneware/ffmpeg-static, vérifiés
#   bundle.json                                                      les versions
# Tout binaire natif du paquet (ELF, Mach-O, PE) est contrôlé par `file` : il doit être de l'architecture cible,
# sinon la fabrication échoue.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=versions.env
source "$HERE/versions.env"

CACHE="${VIDEO_TOOLCHAIN_CACHE:-$HERE/.cache}"
WORK="${VIDEO_TOOLCHAIN_WORK:-$HERE/work}"
OUT="${VIDEO_TOOLCHAIN_OUT:-$HERE/out}/$TOOLCHAIN_VERSION"
ALL_PLATFORMS=(darwin-arm64 darwin-x64 linux-x64 win-x64)
PLATFORMS=("$@")
if [ ${#PLATFORMS[@]} -eq 0 ]; then
  PLATFORMS=("${ALL_PLATFORMS[@]}")
fi

for tool in curl tar gzip unzip xz sha256sum file npm node; do
  command -v "$tool" >/dev/null || { echo "ERREUR : « $tool » est requis." >&2; exit 2; }
done
tar --version | head -1 | grep -q "GNU tar" || { echo "ERREUR : GNU tar est requis (paquets reproductibles)." >&2; exit 2; }

mkdir -p "$CACHE" "$WORK" "$OUT"

# Télécharge une fois dans le cache, puis vérifie l'empreinte épinglée dans upstream.sha256.
fetch() {
  local url="$1" name="$2" expected
  expected="$(awk -v n="$name" '$2 == n { print $1 }' "$HERE/upstream.sha256")"
  [ -n "$expected" ] || { echo "ERREUR : pas d'empreinte épinglée pour $name (upstream.sha256)." >&2; exit 3; }
  if [ ! -f "$CACHE/$name" ]; then
    echo "  ↓ $url"
    curl -fsSL --retry 3 -o "$CACHE/$name.tmp" "$url"
    mv "$CACHE/$name.tmp" "$CACHE/$name"
  fi
  local actual
  actual="$(sha256sum "$CACHE/$name" | awk '{ print $1 }')"
  if [ "$actual" != "$expected" ]; then
    rm -f "$CACHE/$name"
    echo "ERREUR : empreinte inattendue pour $name ($actual ≠ $expected)." >&2
    exit 3
  fi
}

# La signature `file` attendue pour un binaire natif de la plateforme.
arch_pattern() {
  case "$1" in
    darwin-arm64) echo "Mach-O 64-bit .*arm64" ;;
    darwin-x64)   echo "Mach-O 64-bit .*x86_64" ;;
    linux-x64)    echo "ELF 64-bit LSB .*x86-64" ;;
    win-x64)      echo "PE32\+ executable .*x86-64" ;;
  esac
}

build_platform() {
  local plat="$1" npm_os npm_cpu npm_libc="" node_dir node_archive ff exe=""
  case "$plat" in
    darwin-arm64) npm_os=darwin; npm_cpu=arm64; ff=darwin-arm64; node_archive="node-v$NODE_VERSION-$plat.tar.gz" ;;
    darwin-x64)   npm_os=darwin; npm_cpu=x64;   ff=darwin-x64;   node_archive="node-v$NODE_VERSION-$plat.tar.gz" ;;
    linux-x64)    npm_os=linux;  npm_cpu=x64;   ff=linux-x64;    node_archive="node-v$NODE_VERSION-$plat.tar.xz"; npm_libc="--libc=glibc" ;;
    win-x64)      npm_os=win32;  npm_cpu=x64;   ff=win32-x64;    node_archive="node-v$NODE_VERSION-$plat.zip"; exe=".exe" ;;
    *) echo "ERREUR : plateforme inconnue « $plat » (attendu : ${ALL_PLATFORMS[*]})." >&2; exit 2 ;;
  esac
  echo "== $plat"
  local stage="$WORK/$plat"
  rm -rf "$stage"
  mkdir -p "$stage/node" "$stage/hyperframes" "$stage/ffmpeg"

  # 1. Node officiel : le binaire seul (plus aucun npm sur le poste) et sa licence.
  fetch "https://nodejs.org/dist/v$NODE_VERSION/$node_archive" "$node_archive"
  local unpack="$WORK/.node-$plat"
  rm -rf "$unpack"; mkdir -p "$unpack"
  case "$node_archive" in
    *.zip) unzip -q "$CACHE/$node_archive" -d "$unpack" ;;
    *) tar -xf "$CACHE/$node_archive" -C "$unpack" ;;
  esac
  node_dir="$unpack/node-v$NODE_VERSION-$plat"
  if [ -n "$exe" ]; then
    cp "$node_dir/node.exe" "$stage/node/node.exe"
  else
    mkdir -p "$stage/node/bin"
    cp "$node_dir/bin/node" "$stage/node/bin/node"
    chmod 755 "$stage/node/bin/node"
  fi
  cp "$node_dir/LICENSE" "$stage/node/LICENSE"
  rm -rf "$unpack"

  # 2. hyperframes + gsap pour la CIBLE : lockfile versionné (intégrité npm), binaires natifs de la cible,
  #    aucun script d'installation (ils ne s'exécuteraient pas ici et ne servent pas au poste).
  cp "$HERE/npm/package.json" "$HERE/npm/package-lock.json" "$stage/hyperframes/"
  # shellcheck disable=SC2086
  npm ci --prefix "$stage/hyperframes" --os="$npm_os" --cpu="$npm_cpu" $npm_libc --ignore-scripts \
    --omit=dev --no-audit --no-fund --loglevel=error
  rm -rf "$stage/hyperframes/node_modules/.bin"
  find "$stage/hyperframes/node_modules" -type d -name .bin -prune -exec rm -rf {} +
  local hf_version
  hf_version="$(node -p "require('$stage/hyperframes/node_modules/hyperframes/package.json').version")"
  [ "$hf_version" = "$HYPERFRAMES_VERSION" ] || { echo "ERREUR : hyperframes $hf_version ≠ $HYPERFRAMES_VERSION" >&2; exit 4; }

  # 3. ffmpeg / ffprobe statiques.
  for bin in ffmpeg ffprobe; do
    fetch "https://github.com/eugeneware/ffmpeg-static/releases/download/$FFMPEG_TAG/$bin-$ff.gz" "$FFMPEG_TAG-$bin-$ff.gz"
    gzip -dc "$CACHE/$FFMPEG_TAG-$bin-$ff.gz" > "$stage/ffmpeg/$bin$exe"
    chmod 755 "$stage/ffmpeg/$bin$exe"
  done
  fetch "https://github.com/eugeneware/ffmpeg-static/releases/download/$FFMPEG_TAG/$ff.LICENSE" "$FFMPEG_TAG-$ff.LICENSE"
  cp "$CACHE/$FFMPEG_TAG-$ff.LICENSE" "$stage/ffmpeg/LICENSE"
  printf 'ffmpeg/ffprobe statiques — https://github.com/eugeneware/ffmpeg-static/releases/tag/%s (%s)\nSources : https://ffmpeg.org/download.html — licence : voir LICENSE.\n' \
    "$FFMPEG_TAG" "$ff" > "$stage/ffmpeg/README"

  cat > "$stage/bundle.json" <<JSON
{
  "kind": "video-toolchain",
  "version": "$TOOLCHAIN_VERSION",
  "platform": "$plat",
  "node": "$NODE_VERSION",
  "hyperframes": "$HYPERFRAMES_VERSION",
  "gsap": "$GSAP_VERSION",
  "ffmpeg": "$FFMPEG_TAG"
}
JSON

  # 4. Contrôles : aucun lien (le runner les refuse), et TOUT binaire natif est de l'architecture cible.
  if [ -n "$(find "$stage" -type l | head -1)" ]; then
    echo "ERREUR : liens symboliques dans le paquet $plat :" >&2
    find "$stage" -type l >&2
    exit 5
  fi
  local pattern bad=0 count=0
  pattern="$(arch_pattern "$plat")"
  while IFS= read -r -d '' f; do
    local kind
    kind="$(file -b "$f")"
    case "$kind" in
      ELF*|Mach-O*|PE32*)
        count=$((count + 1))
        if ! echo "$kind" | grep -Eq "$pattern"; then
          echo "  ✗ ${f#"$stage"/} : $kind" >&2
          bad=1
        fi ;;
    esac
  done < <(find "$stage" -type f -print0)
  [ "$bad" = 0 ] || { echo "ERREUR : binaires d'une autre architecture dans le paquet $plat." >&2; exit 6; }
  echo "  $count binaires natifs, tous « $pattern »"
  for f in node/bin/node node/node.exe ffmpeg/ffmpeg$exe ffmpeg/ffprobe$exe; do
    [ -f "$stage/$f" ] && echo "  file $f : $(file -b "$stage/$f" | cut -c1-110)"
  done
  find "$stage/hyperframes/node_modules/@esbuild" "$stage/hyperframes/node_modules/@img" -type f \
    \( -name esbuild -o -name esbuild.exe -o -name '*.node' \) -print0 2>/dev/null |
    while IFS= read -r -d '' f; do echo "  file ${f#"$stage"/hyperframes/node_modules/} : $(file -b "$f" | cut -c1-110)"; done

  # 5. Archive reproductible (ordre, propriétaires, dates figés ; gzip sans horodatage).
  tar --sort=name --owner=0 --group=0 --numeric-owner --mtime='2026-01-01 00:00:00Z' --format=pax \
    --pax-option=exthdr.name=%d/PaxHeaders/%f,delete=atime,delete=ctime \
    -C "$stage" -cf - bundle.json node hyperframes ffmpeg | gzip -n -9 > "$OUT/$plat.tar.gz.tmp"
  mv "$OUT/$plat.tar.gz.tmp" "$OUT/$plat.tar.gz"
  echo "  → $OUT/$plat.tar.gz ($(du -h "$OUT/$plat.tar.gz" | cut -f1))"
}

for plat in "${PLATFORMS[@]}"; do
  build_platform "$plat"
done

# Manifeste : tous les paquets présents dans out/<version>/ (une fabrication par plateforme s'accumule).
node - "$OUT" "$TOOLCHAIN_VERSION" "$NODE_VERSION" "$HYPERFRAMES_VERSION" "$GSAP_VERSION" "$FFMPEG_TAG" <<'NODE'
const fs = require('fs'), path = require('path'), crypto = require('crypto');
const [out, version, node, hyperframes, gsap, ffmpeg] = process.argv.slice(2);
const bundles = {};
for (const plat of ['darwin-arm64', 'darwin-x64', 'linux-x64', 'win-x64']) {
  const file = path.join(out, plat + '.tar.gz');
  if (!fs.existsSync(file)) continue;
  const bytes = fs.readFileSync(file);
  bundles[plat] = { file: plat + '.tar.gz', sha256: crypto.createHash('sha256').update(bytes).digest('hex'), size: bytes.length };
}
const manifest = { kind: 'video-toolchain', version, node, hyperframes, gsap, ffmpeg, builtAt: new Date().toISOString(), bundles };
fs.writeFileSync(path.join(out, 'manifest.json'), JSON.stringify(manifest, null, 2) + '\n');
// Un manifeste réécrit invalide toute signature précédente.
fs.rmSync(path.join(out, 'manifest.json.sig'), { force: true });
console.log('Manifeste : ' + path.join(out, 'manifest.json'));
for (const [plat, b] of Object.entries(bundles)) console.log(`  ${plat}  ${b.sha256}  ${(b.size / 1048576).toFixed(1)} Mo`);
NODE
