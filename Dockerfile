# syntax=docker/dockerfile:1.7
# Multi-stage build. REGISTRY defaults to Docker Hub; pass a mirror's library namespace to build
# behind a proxy. A Maven settings.xml, if present, is mounted as a BuildKit secret.
ARG REGISTRY=docker.io/library

FROM ${REGISTRY}/maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN --mount=type=secret,id=m2settings,target=/root/.m2/settings.xml,required=false \
    mvn -B -q dependency:go-offline || true
COPY src ./src
RUN --mount=type=secret,id=m2settings,target=/root/.m2/settings.xml,required=false \
    mvn -B -q -DskipTests package

FROM ${REGISTRY}/eclipse-temurin:21-jre AS runtime
WORKDIR /app
RUN groupadd -r app -g 10001 && useradd -r -g app -u 10001 -s /usr/sbin/nologin app
COPY --from=build /build/target/context-mcp-*.jar /app/app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
