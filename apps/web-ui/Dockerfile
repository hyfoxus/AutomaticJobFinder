FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

# Build context is the repository root: docker build -f apps/web-ui/Dockerfile .
# Every module in settings.gradle must be present, so the other services contribute only their build file.
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
COPY apps/contracts/build.gradle apps/contracts/build.gradle
COPY apps/web-ui apps/web-ui
COPY apps/ingestion-service/build.gradle apps/ingestion-service/build.gradle
COPY apps/scoring-service/build.gradle apps/scoring-service/build.gradle
COPY apps/digest-service/build.gradle apps/digest-service/build.gradle

RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon :apps:web-ui:bootJar -x test

FROM eclipse-temurin:25-jre
WORKDIR /app
# curl is for checking from inside the container that the scoring service answers to its compose name.
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
COPY apps/web-ui/docker/entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh
COPY --from=build /workspace/apps/web-ui/build/libs/*.jar /app/app.jar
ENV JAVA_OPTS=""
EXPOSE 8084
ENTRYPOINT ["/app/entrypoint.sh"]
