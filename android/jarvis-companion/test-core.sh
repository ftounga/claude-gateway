#!/usr/bin/env bash
# F-232 — Teste le cœur PUR du compagnon (paquet `core`, aucune dépendance Android) sans SDK Android :
# javac + JUnit 4 pris dans le dépôt Maven local (ou téléchargés une fois dans build/test-libs).
# Avec le SDK, `gradle :app:testDebugUnitTest` lance les mêmes tests.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
out="$here/build/core-tests"
libs="$here/build/test-libs"
mkdir -p "$out" "$libs"

junit="$HOME/.m2/repository/junit/junit/4.13.2/junit-4.13.2.jar"
hamcrest="$HOME/.m2/repository/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar"
if [[ ! -f "$junit" ]]; then
  junit="$libs/junit-4.13.2.jar"
  [[ -f "$junit" ]] || curl -fsSL -o "$junit" https://repo1.maven.org/maven2/junit/junit/4.13.2/junit-4.13.2.jar
fi
if [[ ! -f "$hamcrest" ]]; then
  hamcrest="$libs/hamcrest-core-1.3.jar"
  [[ -f "$hamcrest" ]] || curl -fsSL -o "$hamcrest" https://repo1.maven.org/maven2/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar
fi

core="$here/app/src/main/java/fr/claudegateway/jarvis/companion/core"
tests="$here/app/src/test/java/fr/claudegateway/jarvis/companion/core"
# --release 17 : le cœur doit rester compilable au niveau de langage du module Android.
javac --release 17 -d "$out" -cp "$junit" "$core"/*.java "$tests"/*.java
classes=$(cd "$tests" && ls *Test.java | sed 's/\.java$//' | sed 's/^/fr.claudegateway.jarvis.companion.core./')
# shellcheck disable=SC2086
java -cp "$out:$junit:$hamcrest" org.junit.runner.JUnitCore $classes
