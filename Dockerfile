# ─────────────────────────────────────────────────────────────────────────────
# Stage 1: Build
# Uses a Maven image with JDK 25 to compile and package the application.
# The build stage is separate from the runtime stage so that Maven, source
# code, and intermediate class files are NOT included in the final image.
# ─────────────────────────────────────────────────────────────────────────────
FROM maven:3.9.16-eclipse-temurin-25 AS build

WORKDIR /build

# Copy the POM first and download dependencies in a separate layer.
# This layer is cached by Docker and only invalidated when pom.xml changes,
# making subsequent builds significantly faster.
COPY pom.xml .
RUN mvn dependency:go-offline -q

# Copy source and build the fat JAR, skipping tests (tests run in CI, not here)
COPY src ./src
RUN mvn package -DskipTests -q

# ─────────────────────────────────────────────────────────────────────────────
# Stage 2: Runtime
# Uses a minimal JRE-only image — no Maven, no source, no compiler toolchain.
# eclipse-temurin:25-jre-alpine is ~190MB vs ~500MB for the full JDK image.
# ─────────────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:25-jre-alpine AS runtime

# Run as a non-root user for security best practice
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

WORKDIR /app

# Copy only the fat JAR from the build stage
COPY --from=build /build/target/fraud-detection-*.jar app.jar

# Expose the Spring Boot default port
EXPOSE 8080

# JVM tuning flags:
#   -XX:+UseContainerSupport        — respects Docker CPU/memory limits
#   -XX:MaxRAMPercentage=75.0       — use up to 75% of container RAM for heap
#   -XX:+ExitOnOutOfMemoryError     — crash fast on OOM rather than limping on
#   -Djava.security.egd=...         — faster entropy for SecureRandom (important in containers)
ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-XX:+ExitOnOutOfMemoryError", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "app.jar"]