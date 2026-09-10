# Contexte de build : la RACINE du dépôt (il faut `backend/` ET `runner/`).
#   docker build -f backend/Dockerfile .
#
# Le binaire du runner (F-38) est empaqueté dans l'image : sans lui, `GET /runner/download`
# répond 404 et l'écran d'appairage propose un téléchargement qui échoue — personne ne peut
# installer de runner sans construire le jar à la main.

# ---- Build du runner (F-38) ----
FROM eclipse-temurin:21-jdk-alpine AS runner-build
WORKDIR /runner

COPY runner/.mvn/ .mvn/
COPY runner/mvnw runner/pom.xml ./
RUN ./mvnw dependency:go-offline -q

COPY runner/src/ src/
RUN ./mvnw clean package -DskipTests -q

# Paquet autonome Windows (F-44 / SF-44-01) : le runner AVEC sa propre JVM, pour les postes où
# aucun Java 21 n'est installable — le cas rencontré chez un client le 2026-09-07 (poste
# d'entreprise en Java 8, pas de droits administrateur).
#
# Construit ICI, sur Linux : `jlink` sait cibler Windows si on lui donne les `jmods` du JDK
# Windows. Aucune machine Windows, aucune matrice d'intégration continue.
COPY runner/package-windows.sh ./
RUN apk add --no-cache curl zip unzip tar \
    && ./package-windows.sh target/claude-runner.jar target

# Paquets autonomes macOS (F-44 / SF-44-03), une archive par architecture. Même technique croisée
# depuis Linux : aucune machine Apple. Deux archives et non une, parce qu'un poste Mac d'entreprise
# verrouillé peut être Intel — et que c'est précisément le poste qu'on ne renouvelle pas.
#
# .tar.gz et non .zip : le zip perdrait le bit exécutable de runtime/bin/java et du lanceur.
COPY runner/package-macos.sh ./
RUN ./package-macos.sh target/claude-runner.jar target aarch64 \
    && ./package-macos.sh target/claude-runner.jar target x64

# Relais local `px` (F-59 / SF-59-01) : la gateway sert elle-même le remède au proxy à
# authentification. L'assistant (F-55) le faisait télécharger depuis GitHub, souvent bloqué PAR
# CATÉGORIE sur un poste d'entreprise — l'utilisateur avait alors besoin du relais pour sortir, et
# d'une sortie pour l'obtenir. Le domaine de la gateway, lui, est forcément autorisé.
#
# Version amont FIGÉE, somme SHA-256 vérifiée, et notice MIT vérifiée : le script échoue plutôt que
# de laisser construire une image qui redistribuerait `px` sans sa licence. `cntlm` (GPL) n'est PAS
# empaqueté — l'assistant continue d'y renvoyer par lien.
COPY runner/package-relay.sh ./
RUN ./package-relay.sh windows target \
    && ./package-relay.sh macos-aarch64 target \
    && ./package-relay.sh linux-x64 target

# ---- Build du backend ----
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app

COPY backend/.mvn/ .mvn/
COPY backend/mvnw backend/pom.xml ./
RUN ./mvnw dependency:go-offline -q

COPY backend/src/ src/
RUN ./mvnw clean package -DskipTests -q

# ---- Run stage ----
FROM eclipse-temurin:21-jre-alpine AS run
WORKDIR /app

RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

COPY --from=build /app/target/*.jar app.jar
# Servi par GET /runner/download quand APP_RUNNER_JAR_PATH pointe ce chemin.
COPY --from=runner-build /runner/target/claude-runner.jar claude-runner.jar
# Paquet autonome Windows (F-44), servi à côté du jar — il ne le remplace pas.
COPY --from=runner-build /runner/target/claude-runner-windows-x64.zip claude-runner-windows-x64.zip
# Paquets autonomes macOS (F-44 / SF-44-03), servis à côté des deux autres.
COPY --from=runner-build /runner/target/claude-runner-macos-aarch64.tar.gz claude-runner-macos-aarch64.tar.gz
COPY --from=runner-build /runner/target/claude-runner-macos-x64.tar.gz claude-runner-macos-x64.tar.gz
# Relais local `px` (F-59), servi par GET /runner/relay/*. La notice MIT voyage AVEC les archives :
# c'est la condition de leur redistribution, et le service refuse de les servir sans elle.
COPY --from=runner-build /runner/target/px-v0.11.0-windows-amd64.zip px-v0.11.0-windows-amd64.zip
COPY --from=runner-build /runner/target/px-v0.11.0-mac-arm64.tar.gz px-v0.11.0-mac-arm64.tar.gz
COPY --from=runner-build /runner/target/px-v0.11.0-linux-glibc-x86_64.tar.gz px-v0.11.0-linux-glibc-x86_64.tar.gz
COPY --from=runner-build /runner/target/px-LICENSE.txt px-LICENSE.txt

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
