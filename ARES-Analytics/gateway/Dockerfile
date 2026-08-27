# Stage 1: Build
FROM gradle:8.9-jdk17 AS build
COPY --chown=gradle:gradle . /home/gradle/src
WORKDIR /home/gradle/src
RUN gradle :gateway:installDist --no-daemon

# Stage 2: Runtime
FROM eclipse-temurin:17-jre
COPY --from=build /home/gradle/src/gateway/build/install/gateway /app
WORKDIR /app
EXPOSE 8080
ENTRYPOINT ["/app/bin/gateway"]
