# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-25 AS build

WORKDIR /build
COPY pom.xml .
COPY src ./src

RUN --mount=type=cache,target=/root/.m2 \
    mvn --batch-mode --no-transfer-progress -DskipTests package \
    && app_jar="$(find target -maxdepth 1 -type f -name 'esig-dss-api-*.jar' ! -name '*.original' -print -quit)" \
    && test -n "$app_jar" \
    && install -D "$app_jar" /out/app.jar

FROM eclipse-temurin:25-jre

LABEL org.opencontainers.image.source="https://github.com/SEUH/esig-dss-api" \
      org.opencontainers.image.licenses="MIT"

WORKDIR /app
COPY --from=build --chown=10001:10001 /out/app.jar /app/app.jar

RUN mkdir -p /var/cache/esig-dss-api \
    && chown 10001:10001 /var/cache/esig-dss-api

ENV ESIG_LOTL_CACHE_DIR=/var/cache/esig-dss-api
EXPOSE 8080
VOLUME ["/var/cache/esig-dss-api"]

USER 10001:10001
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
