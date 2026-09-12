#!/usr/bin/env bash
#
# F-81 / SF-81-01 — Joue les tests de contrat runner ↔ gateway.
#
# `backend/` et `runner/` sont deux projets Maven distincts qui ne se voient pas. Le 2026-09-10, un
# champ renommé d'un seul côté a paralysé tout appairage de machine neuve pendant deux jours sans
# qu'aucune compilation, aucun test, aucune image ne bronche. Le module `contract-tests/` les fait se
# rencontrer ; ce script est la seule façon de le jouer, parce qu'il impose l'ordre et l'option que
# le module ne peut pas imposer lui-même.
#
#   1. le runner est installé (le jar MINCE, classifieur `thin`, est attaché au passage) ;
#   2. le backend est installé SANS son repackage Spring Boot — sinon l'artefact est un fat-jar dont
#      les classes vivent sous BOOT-INF/classes, et dépendre de lui ne compile pas ;
#   3. l'artefact installé est VÉRIFIÉ avant d'aller plus loin ;
#   4. le module est joué.
#
# LE `clean` DU BACKEND N'EST PAS DÉCORATIF. Sans lui, un `verify` antérieur a laissé un fat-jar dans
# `backend/target/` ; maven-jar-plugin le juge « à jour » (les classes n'ont pas bougé), ne le
# reconstruit pas, le repackage est ignoré comme demandé — et `install` publie LE FAT-JAR. Le module
# échoue alors à la compilation avec quarante lignes de « cannot find symbol », c'est-à-dire sur une
# fausse piste. On a déjà perdu une heure sur une fausse piste dans cette histoire.
#
# L'image de production n'est pas concernée : `backend/Dockerfile` copie `runner/...` et
# `backend/...` explicitement, jamais la racine du dépôt. Il ne voit pas `contract-tests/` et ne le
# construira pas — à condition qu'aucun POM réacteur ne soit créé à la racine.
#
# Usage : ./scripts/contract-tests.sh [arguments Maven supplémentaires]

set -euo pipefail

racine="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo "==> Installation du runner (jar mince compris)"
(cd "$racine/runner" && ./mvnw -q install -DskipTests)

echo "==> Installation du backend en jar de bibliothèque (repackage ignoré)"
(cd "$racine/backend" && ./mvnw -q clean install -DskipTests -Dspring-boot.repackage.skip=true)

echo "==> Vérification : l'artefact installé est bien une bibliothèque, pas un fat-jar"
jar_backend="$(ls "$HOME/.m2/repository/fr/claudegateway/claude-gateway-backend"/*/claude-gateway-backend-*.jar 2>/dev/null | head -1)"
if [ -z "$jar_backend" ]; then
  echo "ERREUR : le backend n'est pas installé dans le dépôt local Maven." >&2
  exit 1
fi
if unzip -l "$jar_backend" 2>/dev/null | grep -q "BOOT-INF/"; then
  echo "ERREUR : l'artefact backend installé est un fat-jar Spring Boot (BOOT-INF/)." >&2
  echo "         Ses classes ne sont pas sur le chemin de classes : le module ne compilera pas." >&2
  echo "         Réinstallez-le avec : cd backend && ./mvnw clean install -DskipTests \\" >&2
  echo "                                 -Dspring-boot.repackage.skip=true" >&2
  exit 1
fi

echo "==> Tests de contrat"
(cd "$racine/contract-tests" && ./mvnw test "$@")
