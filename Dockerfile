# Build: docker build --build-arg KEYCLOAK_VERSION=26.8.2 -t keycloak-template-preview .
FROM eclipse-temurin:21-jdk AS build
ARG KEYCLOAK_VERSION=26.8.2
WORKDIR /src
COPY gradlew .
COPY gradle gradle
COPY build.gradle settings.gradle .
RUN ./gradlew --no-daemon -q -Pkeycloak.version=${KEYCLOAK_VERSION} dependencies
COPY src src
RUN ./gradlew --no-daemon -q -Pkeycloak.version=${KEYCLOAK_VERSION} shadowJar

FROM eclipse-temurin:21-jre
COPY --from=build /src/build/libs/keycloak-template-preview.jar /app/app.jar
WORKDIR /work
EXPOSE 8090
ENTRYPOINT ["java", "-jar", "/app/app.jar", "--host=0.0.0.0", "--themes=/work/themes,/work/plugin-themes", "--mocks=/work/mocks"]
