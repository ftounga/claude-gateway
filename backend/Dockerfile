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
RUN apk add --no-cache curl zip unzip \
    && ./package-windows.sh target/claude-runner.jar target

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

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
