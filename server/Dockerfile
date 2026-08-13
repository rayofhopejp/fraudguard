# requirements.md 36章: Lightsailインスタンス上でDockerとして稼働させる想定のビルド。
FROM gradle:8.7-jdk17 AS build
WORKDIR /workspace
COPY . .
RUN gradle buildFatJar --no-daemon

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY --from=build /workspace/build/libs/*-all.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
