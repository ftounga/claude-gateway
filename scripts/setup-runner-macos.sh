#!/bin/bash
# Mise en service du runner claude-gateway sur un poste macOS d'entreprise.
#
# Fait, dans cet ordre, ce qu'une installation manuelle demande :
#   1. lit le proxy du systeme (scutil --proxy, fichier PAC compris) et le declare
#   2. teste l'acces a la gateway et LIT le code de retour (200 / 407 / echec reseau)
#   3. si 407 : verifie si l'authentification integree passe, et monte un relais local
#   4. demande a la gateway ce qu'elle sert : si un paquet autonome existe pour cette machine
#      (F-44 / SF-44-03), il embarque sa propre JVM et AUCUN Java n'est installe ; sinon, repli
#      sur le .jar avec un JDK 21 pose dans le dossier utilisateur (aucun droit admin)
#   5. demande le projet et le code d'appairage, puis lance le runner
#
# Aucune etape n'exige les droits administrateur. Tout est ecrit sous ~/.claude-runner.
#
# Usage :
#   ./setup-runner-macos.sh --code AB12CD --workspace ~/dev/mon-projet
#   ./setup-runner-macos.sh --code AB12CD --workspace ~/dev/mon-projet --gateway https://…/api
#
# Compatible bash 3.2, le bash livre par Apple.

set -u

GATEWAY="https://portal.ng-itconsulting.com/api"
CODE=""
WORKSPACE=""
HOME_DIR="${HOME}/.claude-runner"
JDK_DIR="${HOME_DIR}/jdk"
PX_DIR="${HOME_DIR}/px"
JAR="${HOME_DIR}/claude-runner.jar"
RELAY_PORT="3128"
RELAY_PID=""

# ------------------------------------------------------------------ affichage

BOLD=$(printf '\033[1m'); RED=$(printf '\033[31m'); GREEN=$(printf '\033[32m')
YELLOW=$(printf '\033[33m'); DIM=$(printf '\033[2m'); OFF=$(printf '\033[0m')

step()  { printf "\n%s==>%s %s%s%s\n" "$BOLD" "$OFF" "$BOLD" "$1" "$OFF"; }
ok()    { printf "  %s✓%s %s\n" "$GREEN" "$OFF" "$1"; }
warn()  { printf "  %s!%s %s\n" "$YELLOW" "$OFF" "$1"; }
fail()  { printf "  %s✗%s %s\n" "$RED" "$OFF" "$1"; }
note()  { printf "    %s%s%s\n" "$DIM" "$1" "$OFF"; }

die() { fail "$1"; exit 1; }

cleanup() {
  if [ -n "$RELAY_PID" ] && kill -0 "$RELAY_PID" 2>/dev/null; then
    printf "\n%s==>%s Arret du relais local (pid %s).\n" "$BOLD" "$OFF" "$RELAY_PID"
    kill "$RELAY_PID" 2>/dev/null
  fi
}
trap cleanup EXIT INT TERM

# ------------------------------------------------------------------ arguments

while [ $# -gt 0 ]; do
  case "$1" in
    --gateway)   GATEWAY="${2:-}"; shift 2 ;;
    --code)      CODE="${2:-}"; shift 2 ;;
    --workspace) WORKSPACE="${2:-}"; shift 2 ;;
    --port)      RELAY_PORT="${2:-}"; shift 2 ;;
    -h|--help)
      awk 'NR>1 { if (/^#/) { sub(/^# ?/,""); print } else exit }' "$0"
      exit 0 ;;
    *) die "Option inconnue : $1  (--help pour l'usage)" ;;
  esac
done

[ "$(uname -s)" = "Darwin" ] || die "Ce script est prevu pour macOS. Sur Linux, le runner se lance directement."

mkdir -p "$HOME_DIR" || die "Impossible d'ecrire dans $HOME_DIR"

# --------------------------------------------------- 1. le proxy du systeme

step "1/5  Proxy du systeme"

PROXY=""
PAC_URL=""

# scutil --proxy rend un dictionnaire ; on en tire l'essentiel sans dependance externe.
SCUTIL_OUT=$(scutil --proxy 2>/dev/null || true)
value_of() { printf '%s\n' "$SCUTIL_OUT" | awk -v k="$1" '$1==k {print $3; exit}'; }

if [ "$(value_of HTTPSEnable)" = "1" ]; then
  HOST=$(value_of HTTPSProxy); PORT=$(value_of HTTPSPort)
  [ -n "$HOST" ] && PROXY="http://${HOST}:${PORT:-8080}"
elif [ "$(value_of HTTPEnable)" = "1" ]; then
  HOST=$(value_of HTTPProxy); PORT=$(value_of HTTPPort)
  [ -n "$HOST" ] && PROXY="http://${HOST}:${PORT:-8080}"
fi

if [ -z "$PROXY" ] && [ "$(value_of ProxyAutoConfigEnable)" = "1" ]; then
  PAC_URL=$(value_of ProxyAutoConfigURLString)
  if [ -n "$PAC_URL" ]; then
    note "Configuration automatique (PAC) : $PAC_URL"
    # Le PAC ecrit l'adresse en clair sur une ligne PROXY hote:port.
    PAC_BODY=$(curl -fsS --max-time 10 "$PAC_URL" 2>/dev/null || true)
    if [ -n "$PAC_BODY" ]; then
      CANDIDATE=$(printf '%s\n' "$PAC_BODY" \
        | grep -oE 'PROXY[[:space:]]+[A-Za-z0-9._-]+:[0-9]+' \
        | head -1 | awk '{print $2}')
      [ -n "$CANDIDATE" ] && PROXY="http://${CANDIDATE}"
    fi
  fi
fi

# Une variable deja posee dans l'environnement l'emporte : l'operateur sait ce qu'il fait.
if [ -n "${HTTPS_PROXY:-}" ]; then
  PROXY="$HTTPS_PROXY"
  ok "Proxy repris de l'environnement : $PROXY"
elif [ -n "$PROXY" ]; then
  ok "Proxy du systeme : $PROXY"
else
  ok "Aucun proxy declare — connexion directe."
fi

# La liste d'exceptions de macOS est un tableau multi-lignes : la lire ici donnerait une valeur
# fausse. NO_PROXY reste donc celui de l'environnement, complete du minimum utile.
export NO_PROXY="${NO_PROXY:-localhost,127.0.0.1}"

curl_probe() { # $1… : options supplementaires ; ecrit le code HTTP sur stdout
  local args=""
  [ -n "$PROXY" ] && args="-x $PROXY"
  # shellcheck disable=SC2086
  curl -sS -o /dev/null -w '%{http_code}' --max-time 20 $args "$@" \
    "${GATEWAY%/}/actuator/health" 2>/dev/null || printf '000'
}

# ------------------------------------------------- 2. l'acces a la gateway

step "2/5  Acces a la gateway"
note "Cible : ${GATEWAY%/}/actuator/health"

CODE_HTTP=$(curl_probe)
USE_RELAY="no"

case "$CODE_HTTP" in
  2*|3*|404)
    # Toute reponse HTTP prouve que la voie est libre : on ne juge pas la sante du service.
    ok "La gateway repond (code $CODE_HTTP). La voie est libre."
    ;;
  407)
    warn "Le proxy exige une authentification (407)."
    note "Test de l'authentification integree (Kerberos, puis NTLM)…"
    NEGO=$(curl_probe --proxy-negotiate --proxy-user :)
    NTLM=$(curl_probe --proxy-ntlm --proxy-user :)
    if [ "$NEGO" = "200" ] || [ "$NTLM" = "200" ]; then
      ok "L'authentification integree passe (Kerberos=$NEGO, NTLM=$NTLM)."
      note "Java ne sait pas la porter : un relais local va la porter a sa place."
      USE_RELAY="yes"
    else
      fail "L'authentification integree ne passe pas non plus (Kerberos=$NEGO, NTLM=$NTLM)."
      note "Le proxy attend probablement des identifiants applicatifs."
      note "Demandez a votre DSI une exception sur $(printf '%s' "$GATEWAY" | awk -F/ '{print $3}')"
      note "ou un compte de service, puis relancez ce script."
      exit 3
    fi
    ;;
  000)
    fail "Aucune reponse : ni resolution DNS ni connexion."
    if [ -z "$PROXY" ]; then
      note "Aucun proxy n'a ete trouve dans la configuration du systeme."
      note "Si votre navigateur atteint la gateway, c'est qu'un proxy est configure ailleurs :"
      note "  scutil --proxy        (voir la configuration du systeme)"
      note "  networksetup -getwebproxy Wi-Fi"
      note "Puis relancez :  HTTPS_PROXY=http://hote:port $0 …"
    else
      note "Le proxy $PROXY n'a pas repondu. Verifiez l'adresse et le port."
    fi
    exit 3
    ;;
  *)
    fail "Reponse inattendue du reseau (code $CODE_HTTP)."
    exit 3
    ;;
esac

# ------------------------------------------------- 3. le relais local (407)

if [ "$USE_RELAY" = "yes" ]; then
  step "3/5  Relais local d'authentification"

  PX_BIN=""
  ARCH=$(uname -m)
  if [ -x "${PX_DIR}/px" ]; then
    PX_BIN="${PX_DIR}/px"
  elif command -v px >/dev/null 2>&1; then
    PX_BIN=$(command -v px)
  elif [ "$ARCH" = "arm64" ]; then
    note "Telechargement de px (relais qui porte l'authentification du systeme)…"
    mkdir -p "$PX_DIR"
    PX_URL="https://github.com/genotrance/px/releases/download/v0.11.0/px-v0.11.0-mac-arm64.tar.gz"
    if curl -fsSL ${PROXY:+-x "$PROXY"} --proxy-negotiate --proxy-user : \
         "$PX_URL" -o "${PX_DIR}/px.tar.gz" 2>/dev/null \
       || curl -fsSL ${PROXY:+-x "$PROXY"} --proxy-ntlm --proxy-user : \
         "$PX_URL" -o "${PX_DIR}/px.tar.gz" 2>/dev/null; then
      tar -xzf "${PX_DIR}/px.tar.gz" -C "$PX_DIR" 2>/dev/null
      PX_BIN=$(find "$PX_DIR" -type f -name px -perm -u+x | head -1)
    fi
  fi

  if [ -z "$PX_BIN" ]; then
    fail "Aucun relais local disponible."
    note "Sur un Mac Intel, px n'a pas de binaire publie. Deux voies :"
    note "  pip3 install --user px-proxy   puis relancez ce script"
    note "  brew install cntlm             si Homebrew est disponible"
    exit 4
  fi

  PROXY_HOSTPORT=$(printf '%s' "$PROXY" | sed 's#^https\{0,1\}://##')
  "$PX_BIN" --proxy="$PROXY_HOSTPORT" --port="$RELAY_PORT" >"${HOME_DIR}/px.log" 2>&1 &
  RELAY_PID=$!
  note "Relais lance (pid $RELAY_PID), journal : ${HOME_DIR}/px.log"

  # Le relais met une seconde ou deux a ouvrir son port.
  RELAY_OK="no"
  for _ in 1 2 3 4 5 6 7 8 9 10; do
    sleep 1
    RC=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 10 \
         -x "http://127.0.0.1:${RELAY_PORT}" "${GATEWAY%/}/actuator/health" 2>/dev/null || printf '000')
    case "$RC" in 2*|3*|404) RELAY_OK="yes"; break ;; esac
  done

  [ "$RELAY_OK" = "yes" ] || { fail "Le relais n'a pas pris la main. Voir ${HOME_DIR}/px.log"; exit 4; }

  ok "Le relais porte l'authentification : 127.0.0.1:${RELAY_PORT}"
  PROXY="http://127.0.0.1:${RELAY_PORT}"
else
  step "3/5  Relais local — inutile ici"
  ok "Le proxy n'exige pas d'authentification."
fi

export HTTPS_PROXY="$PROXY"
export HTTP_PROXY="$PROXY"

# ------------------------------------------- 4. le runner : paquet ou jar

step "4/5  Runner"

case "$(uname -m)" in
    arm64) PKG_KEY="macosAarch64Package"; PKG_ROUTE="macos-aarch64"; ADOPT_ARCH="aarch64" ;;
    *)     PKG_KEY="macosX64Package";     PKG_ROUTE="macos-x64";     ADOPT_ARCH="x64" ;;
esac

# La gateway dit elle-meme ce qu'elle sert (F-44 / SF-44-02) : une passerelle anterieure aux
# paquets macOS n'annonce que le jar, et ce script doit rester utilisable devant elle.
FORMATS=$(curl -fsS --max-time 30 "${GATEWAY%/}/runner/download/formats" 2>/dev/null || true)
USE_PACKAGE="no"
case "$FORMATS" in
    *"\"${PKG_KEY}\":true"*) USE_PACKAGE="yes" ;;
esac

PKG_LAUNCHER=""
JAVA_BIN=""

if [ "$USE_PACKAGE" = "yes" ]; then
    ok "La passerelle sert un paquet autonome pour cette machine ($PKG_ROUTE)."
    note "Il embarque sa propre JVM : aucun Java a installer."

    curl -fsSL --max-time 600 "${GATEWAY%/}/runner/download/${PKG_ROUTE}" -o "${HOME_DIR}/runner.tar.gz" \
        || die "Telechargement du paquet impossible depuis ${GATEWAY%/}/runner/download/${PKG_ROUTE}"

    SIZE=$(wc -c < "${HOME_DIR}/runner.tar.gz")
    if [ "$SIZE" -lt 20000000 ]; then
        die "Paquet tronque (${SIZE} octets) — le proxy a probablement rendu une page de blocage."
    fi

    rm -rf "${HOME_DIR}/claude-runner"
    tar -xzf "${HOME_DIR}/runner.tar.gz" -C "$HOME_DIR" || die "Archive du runner illisible."
    rm -f "${HOME_DIR}/runner.tar.gz"

    PKG_LAUNCHER="${HOME_DIR}/claude-runner/claude-runner.command"
    [ -x "$PKG_LAUNCHER" ] || die "Lanceur absent ou non executable : $PKG_LAUNCHER"

    # Le lanceur leve lui-meme la quarantaine, mais il doit d'abord pouvoir demarrer.
    xattr -dr com.apple.quarantine "${HOME_DIR}/claude-runner" 2>/dev/null || true

    ok "Paquet installe : ${HOME_DIR}/claude-runner ($(du -sh "${HOME_DIR}/claude-runner" | awk '{print $1}'))"
else
    warn "Cette passerelle ne sert pas de paquet macOS — repli sur le .jar, qui exige Java 21."

    java_major() { # $1 = binaire java ; ecrit la version majeure, ou rien
        "$1" -version 2>&1 | awk -F'"' '/version/ {split($2,v,"."); print (v[1]=="1")?v[2]:v[1]; exit}'
    }

    if [ -x "${JDK_DIR}/Contents/Home/bin/java" ]; then
        JAVA_BIN="${JDK_DIR}/Contents/Home/bin/java"
    elif [ -x "${JDK_DIR}/bin/java" ]; then
        JAVA_BIN="${JDK_DIR}/bin/java"
    elif command -v java >/dev/null 2>&1; then
        JAVA_BIN=$(command -v java)
    fi

    MAJOR=""
    [ -n "$JAVA_BIN" ] && MAJOR=$(java_major "$JAVA_BIN")

    if [ -n "$MAJOR" ] && [ "$MAJOR" -ge 21 ] 2>/dev/null; then
        ok "Java $MAJOR present : $JAVA_BIN"
    else
        [ -n "$MAJOR" ] && warn "Java $MAJOR present, mais le runner exige Java 21." \
                        || warn "Aucun Java trouve sur ce poste."
        note "Installation d'un JDK 21 dans $JDK_DIR (aucun droit administrateur)..."

        ADOPT_URL="https://api.adoptium.net/v3/binary/latest/21/ga/mac/${ADOPT_ARCH}/jdk/hotspot/normal/eclipse"
        mkdir -p "$JDK_DIR"
        curl -fsSL --max-time 600 "$ADOPT_URL" -o "${HOME_DIR}/jdk.tar.gz" \
            || die "Telechargement du JDK impossible. Verifiez que api.adoptium.net est autorise."
        tar -xzf "${HOME_DIR}/jdk.tar.gz" -C "$JDK_DIR" --strip-components=1 \
            || die "Archive JDK illisible."
        rm -f "${HOME_DIR}/jdk.tar.gz"

        if [ -x "${JDK_DIR}/Contents/Home/bin/java" ]; then
            JAVA_BIN="${JDK_DIR}/Contents/Home/bin/java"
        elif [ -x "${JDK_DIR}/bin/java" ]; then
            JAVA_BIN="${JDK_DIR}/bin/java"
        else
            die "JDK installe mais binaire java introuvable sous $JDK_DIR"
        fi

        # macOS met en quarantaine ce qui vient du reseau : sans cela, le premier lancement est refuse.
        xattr -dr com.apple.quarantine "$JDK_DIR" 2>/dev/null || true
        ok "Java $(java_major "$JAVA_BIN") installe : $JAVA_BIN"
    fi

    curl -fsSL --max-time 300 "${GATEWAY%/}/runner/download" -o "$JAR" \
        || die "Telechargement du runner impossible depuis ${GATEWAY%/}/runner/download"
    ok "Runner telecharge : $JAR ($(du -h "$JAR" | awk '{print $1}'))"
fi

# ------------------------------------------------- 5. projet, code, lancement

step "5/5  Appairage"

if [ -z "$WORKSPACE" ]; then
    printf "\n  Racine du projet sur cette machine : "
    read -r WORKSPACE
fi
WORKSPACE="${WORKSPACE/#\~/$HOME}"
[ -d "$WORKSPACE" ] || die "Ce dossier n'existe pas : $WORKSPACE"

if [ -z "$CODE" ]; then
    printf "  Code d'appairage (genere dans l'application, valable quelques minutes) : "
    read -r CODE
fi
[ -n "$CODE" ] || die "Aucun code d'appairage fourni."

printf "\n%s==>%s Tout est en place. Lancement du runner.\n" "$BOLD" "$OFF"
note "Proxy    : ${HTTPS_PROXY:-aucun}"
note "Runner   : $([ "$USE_PACKAGE" = "yes" ] && echo "paquet autonome (JVM incluse)" || echo "jar + $JAVA_BIN")"
note "Projet   : $WORKSPACE"
note "Ctrl-C arrete le runner (et le relais local s'il a ete lance)."
printf "\n"

# Pas de `exec` : il remplacerait ce processus, et le relais local ne serait plus arrete
# a la sortie (le trap disparaitrait avec le shell).
if [ "$USE_PACKAGE" = "yes" ]; then
    "$PKG_LAUNCHER" --gateway "$GATEWAY" --workspace "$WORKSPACE" --code "$CODE"
else
    "$JAVA_BIN" -jar "$JAR" --gateway "$GATEWAY" --workspace "$WORKSPACE" --code "$CODE"
fi
RUNNER_RC=$?

printf "\n%s==>%s Runner arrete (code %s).\n" "$BOLD" "$OFF" "$RUNNER_RC"
exit "$RUNNER_RC"
