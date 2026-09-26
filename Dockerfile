FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace
COPY --chmod=0755 gradlew ./gradlew
COPY gradle ./gradle
COPY build.gradle settings.gradle ./
COPY src/main ./src/main
RUN ./gradlew --no-daemon bootJar -x test

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN groupadd --gid 10001 hanmaum \
    && useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin hanmaum
COPY --from=build --chown=10001:10001 /workspace/build/libs/hanmaum-backend.jar ./app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=65.0", "-jar", "/app/app.jar"]
