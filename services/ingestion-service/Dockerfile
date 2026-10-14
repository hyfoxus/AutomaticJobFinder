# Build from the repository root: docker build -f services/ingestion-service/Dockerfile .
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
COPY contracts contracts
COPY services/ingestion-service services/ingestion-service
RUN ./gradlew --no-daemon :services:ingestion-service:bootJar -x test

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /workspace/services/ingestion-service/build/libs/*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
