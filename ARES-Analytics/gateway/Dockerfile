# Stage 1: Build using the monorepo Gradle wrapper (8.14.5) and pinned release metadata
FROM eclipse-temurin:17-jdk AS build
WORKDIR /workspace
COPY build-logic ./build-logic
COPY release ./release
COPY ARES-Analytics ./ARES-Analytics
WORKDIR /workspace/ARES-Analytics
RUN chmod +x ./gradlew && ./gradlew :gateway:installDist --no-daemon

# Stage 2: Minimal non-root runtime
FROM eclipse-temurin:17-jre
RUN groupadd --system ares && useradd --system --gid ares --home-dir /app --shell /usr/sbin/nologin ares
COPY --from=build --chown=ares:ares /workspace/ARES-Analytics/gateway/build/install/gateway /app
WORKDIR /app
USER ares
EXPOSE 8080
ENTRYPOINT ["/app/bin/gateway"]
