FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

# Build context is the repository root: docker build -f apps/scoring-service/Dockerfile .
# Every module in settings.gradle must be present, so the other services contribute only their build file.
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
COPY apps/contracts apps/contracts
COPY apps/scoring-service apps/scoring-service
COPY apps/ingestion-service/build.gradle apps/ingestion-service/build.gradle

RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon :apps:scoring-service:bootJar -x test

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY apps/scoring-service/docker/entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh
COPY --from=build /workspace/apps/scoring-service/build/libs/*.jar /app/app.jar
ENV JAVA_OPTS=""
EXPOSE 8082
ENTRYPOINT ["/app/entrypoint.sh"]
