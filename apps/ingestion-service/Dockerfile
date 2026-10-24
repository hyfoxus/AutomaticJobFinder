FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

# Build context is the repository root: docker build -f apps/ingestion-service/Dockerfile .
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
COPY apps/contracts apps/contracts
COPY apps/ingestion-service apps/ingestion-service
COPY apps/scoring-service/build.gradle apps/scoring-service/build.gradle
COPY apps/digest-service/build.gradle apps/digest-service/build.gradle

RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon :apps:ingestion-service:bootJar -x test

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY apps/ingestion-service/docker/entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh
COPY --from=build /workspace/apps/ingestion-service/build/libs/*.jar /app/app.jar
ENV JAVA_OPTS=""
EXPOSE 8081
ENTRYPOINT ["/app/entrypoint.sh"]
