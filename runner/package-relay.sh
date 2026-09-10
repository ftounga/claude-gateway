#!/bin/sh
# Empaquette le relais local `px` pour qu'il soit servi PAR LA GATEWAY (F-59 / SF-59-01).
#
# Pourquoi : l'assistant proxy (F-55) fait télécharger `px` depuis GitHub. Sur un poste
# d'entreprise, GitHub est souvent bloqué PAR CATÉGORIE — indépendamment du proxy à
# authentification. L'utilisateur a alors besoin du relais pour sortir, et d'une sortie pour obtenir
# le relais. Le domaine de la gateway, lui, est forcément autorisé : sinon rien du produit ne
# fonctionne. Elle sert donc elle-même le relais.
#
# LICENCE — la condition, pas une note de bas de page. `px` est sous MIT : la redistribution est
# licite À CONDITION que la notice de licence et le copyright accompagnent la copie. Ce script
# VÉRIFIE que l'archive amont porte cette notice et ÉCHOUE sinon : aucune image ne peut être
# construite sans elle. `cntlm`, sous GPL, n'est PAS empaqueté — fournir le binaire imposerait de
# fournir les sources correspondantes ; l'assistant continue d'y renvoyer par lien.
#
# L'archive est copiée VERBATIM, sous son nom amont : l'utilisateur reçoit octet pour octet ce que
# le projet publie, somme SHA-256 comprise — et peut donc la vérifier lui-même auprès de l'amont.
#
# Usage : ./package-relay.sh <windows|macos-aarch64|linux-x64> <repertoire-de-sortie>
set -eu

PLATFORM="${1:?usage: package-relay.sh <windows|macos-aarch64|linux-x64> <sortie>}"
OUT="${2:?usage: package-relay.sh <windows|macos-aarch64|linux-x64> <sortie>}"

# Version amont FIGÉE et citée. Jamais `latest` : une image reproductible ne dépend pas de ce que le
# projet aura publié entre-temps, et la notice vérifiée est celle de CETTE version.
PX_VERSION="v0.11.0"
PX_REPO="https://github.com/genotrance/px/releases/download/${PX_VERSION}"

# Les trois plateformes retenues (cadrage F-59). macOS Intel n'y est pas parce que le projet ne le
# publie pas — en fabriquer un reviendrait à maintenir une version autre que celle publiée en amont,
# explicitement hors périmètre. L'assistant y garde le chemin `pip3`.
case "$PLATFORM" in
    windows)        ARCHIVE="px-${PX_VERSION}-windows-amd64.zip" ;;
    macos-aarch64)  ARCHIVE="px-${PX_VERSION}-mac-arm64.tar.gz" ;;
    linux-x64)      ARCHIVE="px-${PX_VERSION}-linux-glibc-x86_64.tar.gz" ;;
    *)
        echo "ERREUR : plateforme inconnue '$PLATFORM' (attendu windows, macos-aarch64 ou linux-x64)." >&2
        exit 1
        ;;
esac

LICENSE_OUT="${OUT}/px-LICENSE.txt"
WORK="${OUT}/.work-relay-${PLATFORM}"
rm -rf "$WORK" "${OUT}/${ARCHIVE}"
mkdir -p "$WORK"

echo "→ Téléchargement de px ${PX_VERSION} (${PLATFORM})"
curl -fsSL -o "${WORK}/${ARCHIVE}" "${PX_REPO}/${ARCHIVE}"
curl -fsSL -o "${WORK}/${ARCHIVE}.sha256" "${PX_REPO}/${ARCHIVE}.sha256"

# Le build échoue plutôt que de servir une archive douteuse (même garde-fou qu'en F-44, en plus
# strict : le projet publie la somme, autant s'en servir). Le fichier amont ne contient que le
# condensé, sans nom de fichier — on compare les deux chaînes.
EXPECTED=$(tr -d ' \t\r\n' < "${WORK}/${ARCHIVE}.sha256")
ACTUAL=$(sha256sum "${WORK}/${ARCHIVE}" | cut -d' ' -f1)
if [ "$EXPECTED" != "$ACTUAL" ]; then
    echo "ERREUR : somme SHA-256 inattendue pour ${ARCHIVE}." >&2
    echo "  attendu : ${EXPECTED}" >&2
    echo "  obtenu  : ${ACTUAL}" >&2
    exit 1
fi

echo "→ Vérification de la notice de licence (condition MIT)"
case "$ARCHIVE" in
    *.zip)     unzip -p "${WORK}/${ARCHIVE}" LICENSE.txt > "${WORK}/LICENSE.txt" ;;
    *.tar.gz)  tar -xzf "${WORK}/${ARCHIVE}" -O ./LICENSE.txt > "${WORK}/LICENSE.txt" ;;
esac

if [ ! -s "${WORK}/LICENSE.txt" ]; then
    echo "ERREUR : aucune notice LICENSE.txt dans ${ARCHIVE} — redistribution impossible." >&2
    exit 1
fi

# Deux marqueurs, pas un : le titre seul se retrouverait dans n'importe quel README qui cite MIT ;
# la clause de conservation du copyright est ce que la licence EXIGE de transmettre.
if ! grep -q "MIT License" "${WORK}/LICENSE.txt" \
    || ! grep -q "above copyright notice" "${WORK}/LICENSE.txt"; then
    echo "ERREUR : LICENSE.txt de ${ARCHIVE} n'est pas la notice MIT attendue." >&2
    exit 1
fi

# La notice est aussi servie À PART (GET /runner/relay/license) : l'écran doit pouvoir la montrer
# AVANT de faire télécharger 21 Mo. Elle est identique d'une plateforme à l'autre — la réécrire à
# chaque appel est sans conséquence et garde le script indépendant de l'ordre d'exécution.
cp "${WORK}/LICENSE.txt" "$LICENSE_OUT"

cp "${WORK}/${ARCHIVE}" "${OUT}/${ARCHIVE}"
rm -rf "$WORK"

echo "✓ $(ls -la "${OUT}/${ARCHIVE}" | awk '{printf "%.1f Mo", $5/1048576}') → ${OUT}/${ARCHIVE} (+ px-LICENSE.txt)"
