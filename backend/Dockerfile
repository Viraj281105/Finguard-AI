# syntax=docker/dockerfile:1
FROM eclipse-temurin:25-jdk AS builder
WORKDIR /build

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B dependency:go-offline

COPY src/main/ src/main/
RUN ./mvnw -B -DskipTests package && \
    java -Djarmode=tools -jar target/backend-*.jar extract --layers --destination extracted

FROM eclipse-temurin:25-jre AS runtime
RUN groupadd --system --gid 10001 finguard && \
    useradd --system --uid 10001 --gid finguard --create-home finguard

WORKDIR /app
COPY --from=builder --chown=finguard:finguard /build/extracted/dependencies/ ./
COPY --from=builder --chown=finguard:finguard /build/extracted/spring-boot-loader/ ./
COPY --from=builder --chown=finguard:finguard /build/extracted/snapshot-dependencies/ ./
COPY --from=builder --chown=finguard:finguard /build/extracted/application/ ./

USER 10001:10001
EXPOSE 8080 8081

HEALTHCHECK --interval=10s --timeout=3s --start-period=25s --retries=6 \
  CMD curl -fsS http://127.0.0.1:8081/actuator/health/readiness || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-XX:+ExitOnOutOfMemoryError", "org.springframework.boot.loader.launch.JarLauncher"]

