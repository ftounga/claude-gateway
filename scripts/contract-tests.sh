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
#   3. le module est joué.
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
(cd "$racine/backend" && ./mvnw -q install -DskipTests -Dspring-boot.repackage.skip=true)

echo "==> Tests de contrat"
(cd "$racine/contract-tests" && ./mvnw test "$@")
