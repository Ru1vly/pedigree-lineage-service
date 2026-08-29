# Build Stage
#
# The build runs in the official Maven image rather than a bare JDK. This repository does not
# commit a Maven wrapper, and eclipse-temurin ships a JDK with no build tool at all - so the
# previous `./mvnw package || mvn package` had no working half: no ./mvnw to run, and no mvn on
# the PATH to fall back to. `docker compose up --build`, the first command in the README, died
# on that line. There is no `||` here on purpose: if the build breaks, it must fail loudly.
FROM maven:3.9.9-eclipse-temurin-21 AS builder
WORKDIR /app

# Dependencies resolve in their own layer, keyed on pom.xml alone, so editing a source file does
# not re-download the dependency tree on every rebuild.
COPY pom.xml .
RUN mvn -B -e dependency:go-offline

# config/ carries the Checkstyle ruleset and suppressions. The build binds checkstyle:check to the
# validate phase, so `package` fails without them - copying only pom.xml and src/ is not enough.
COPY config ./config
COPY src ./src
RUN mvn -B -e clean package -DskipTests

# Production Stage
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

# Copy before dropping privileges, with explicit ownership, so the runtime user can read the jar.
COPY --from=builder --chown=appuser:appgroup /app/target/*.jar app.jar

USER appuser
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
