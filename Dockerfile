# Single-container demo image: the React build is served by Spring Boot
# itself, so free hosting tiers (one service, one port) can run the whole
# stack. The docker-compose setup remains the two-service local topology.

# ── Frontend build ───────────────────────────────────────────
FROM node:22-alpine AS frontend
WORKDIR /app
COPY frontend/package*.json ./
RUN npm ci
COPY frontend .
RUN npm run build

# ── Backend build (frontend bundled as static resources) ─────
FROM eclipse-temurin:21-jdk-alpine AS backend
WORKDIR /app
COPY backend/.mvn .mvn
COPY backend/mvnw backend/pom.xml ./
RUN ./mvnw -q -B dependency:go-offline
COPY backend/src src
COPY --from=frontend /app/dist src/main/resources/static
RUN ./mvnw -q -B package -DskipTests

# ── Runtime ──────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S harbormaster && adduser -S harbormaster -G harbormaster
USER harbormaster
WORKDIR /app
COPY --from=backend /app/target/harbormaster-backend-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
