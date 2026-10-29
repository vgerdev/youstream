# YouStream: HTTP (8080) + SFTP (2222) + transcode workers in one container.
#
#   docker build -t youstream .
#   docker run -p 8080:8080 -p 2222:2222 -v youstream-data:/data \
#     -v ./conf/local.conf:/opt/youstream/conf/local.conf:ro youstream
#
# Media, the SFTP inboxes and the SSH host key live in /data (keep it on a volume).
# Not built/tested on the development machine (no Docker there).

FROM node:22-bookworm-slim AS node

FROM eclipse-temurin:25-jdk-noble AS build
# Gradle builds the SolidJS frontend with the Node.js on PATH
COPY --from=node /usr/local/ /usr/local/
WORKDIR /src
COPY . .
RUN chmod +x gradlew && ./gradlew --no-daemon installDist -x test

FROM eclipse-temurin:25-jre-noble
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --home-dir /data youstream \
    && mkdir -p /data && chown youstream /data
COPY --from=build /src/youstream-server/build/install/youstream /opt/youstream
WORKDIR /opt/youstream
ENV STORAGE_ROOT=/data
VOLUME /data
EXPOSE 8080 2222
USER youstream
ENTRYPOINT ["/opt/youstream/bin/youstream"]
