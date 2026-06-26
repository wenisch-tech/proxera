# syntax=docker/dockerfile:1.25@sha256:0adf442eae370b6087e08edc7c50b552d80ddf261576f4ebd6421006b2461f12
FROM cgr.dev/chainguard/jre:latest AS jvm-runtime

WORKDIR /app

ARG BUILD_DATE
ARG BUILD_VERSION
ARG BUILD_REVISION

LABEL org.opencontainers.image.title="Proxera" \
      org.opencontainers.image.description="Self-hosted reverse tunnel — expose LAN services to the internet" \
      org.opencontainers.image.url="https://github.com/wenisch-tech/proxera" \
      org.opencontainers.image.source="https://github.com/wenisch-tech/proxera" \
      org.opencontainers.image.documentation="https://github.com/wenisch-tech/proxera/tree/main/docs" \
      org.opencontainers.image.authors="JFWenisch" \
      org.opencontainers.image.licenses="AGPL-3.0" \
      org.opencontainers.image.vendor="wenisch.tech" \
      org.opencontainers.image.version="${BUILD_VERSION}" \
      org.opencontainers.image.revision="${BUILD_REVISION}" \
      org.opencontainers.image.created="${BUILD_DATE}"

ENV JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=50.0 \
    -XX:InitialRAMPercentage=5.0 -XX:+UseG1GC -Djava.security.egd=file:/dev/urandom"

COPY target/proxera.jar /app/app.jar

# 8080 — proxy + admin UI / API port
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
