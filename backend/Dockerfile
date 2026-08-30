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

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
