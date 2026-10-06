FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
RUN mvn -B package -DskipTests

FROM eclipse-temurin:21-jre-alpine

# Release identity. The version itself comes from the jar's build-info; the commit and build time are supplied by the image build
# (docker compose -f docker-compose.pilot.yml build) and are never read from the running environment.
ARG APP_BUILD_COMMIT=unknown
ARG APP_BUILD_TIME=unknown
ARG APP_VERSION=0.1.0-rc1
LABEL org.opencontainers.image.title="fdmultimedia-api" \
      org.opencontainers.image.version="${APP_VERSION}" \
      org.opencontainers.image.revision="${APP_BUILD_COMMIT}" \
      org.opencontainers.image.created="${APP_BUILD_TIME}"
ENV APP_BUILD_COMMIT=${APP_BUILD_COMMIT}

# Runs as an unprivileged user. The only writable path the application needs is /tmp.
RUN addgroup -S -g 10001 app && adduser -S -u 10001 -G app -h /app app
WORKDIR /app
COPY --from=build --chown=app:app /build/target/*.jar app.jar
USER app

EXPOSE 8080

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
