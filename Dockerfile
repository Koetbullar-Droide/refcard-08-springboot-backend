# --- Stage 1: Build ---
# Offizielles Maven-Image: Maven ist schon drin, der Wrapper muss nichts herunterladen
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Erst nur pom.xml -> Abhängigkeiten landen in einem eigenen (gecachten) Layer
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q clean package -DskipTests

# --- Stage 2: Runtime ---
FROM eclipse-temurin:21-jre-alpine
# Version aus dem Git-Tag (setzt die Pipeline), sichtbar unter /actuator/info und als Image-Label
ARG APP_VERSION=dev
ARG GIT_SHA=unknown
ENV APP_VERSION=$APP_VERSION
LABEL org.opencontainers.image.version=$APP_VERSION \
      org.opencontainers.image.revision=$GIT_SHA \
      org.opencontainers.image.source=https://github.com/Koetbullar-Droide/refcard-08-springboot-backend
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
