# Production Worker image: the agent plus exactly the media tooling it needs (FFmpeg/FFprobe and the OpenAI Whisper CLI with the
# "base" model baked in), so no host-only binary or model path is ever assumed. Ollama stays external; see docs/CONFIGURATION.md.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B package -DskipTests

FROM eclipse-temurin:21-jre-jammy

ARG APP_BUILD_COMMIT=unknown
ARG APP_BUILD_TIME=unknown
ARG APP_VERSION=0.1.0-rc1
ARG WHISPER_MODEL=base
LABEL org.opencontainers.image.title="fdmultimedia-worker" \
      org.opencontainers.image.version="${APP_VERSION}" \
      org.opencontainers.image.revision="${APP_BUILD_COMMIT}" \
      org.opencontainers.image.created="${APP_BUILD_TIME}"

RUN apt-get update \
 && apt-get -y upgrade \
 && apt-get install -y --no-install-recommends ffmpeg python3 python3-venv python3-pip ca-certificates \
 && rm -rf /var/lib/apt/lists/*

# CPU-only PyTorch keeps the image a few GB smaller than the default CUDA wheels; Whisper runs with --fp16 False in the agent.
ENV XDG_CACHE_HOME=/opt/cache
RUN python3 -m venv /opt/whisper \
 && /opt/whisper/bin/pip install --no-cache-dir --upgrade pip setuptools wheel \
 && /opt/whisper/bin/pip install --no-cache-dir torch --index-url https://download.pytorch.org/whl/cpu \
 && /opt/whisper/bin/pip install --no-cache-dir openai-whisper \
 && /opt/whisper/bin/python -c "import whisper; whisper.load_model('${WHISPER_MODEL}')" \
 && /opt/whisper/bin/pip uninstall -y pip setuptools wheel \
 && /opt/whisper/bin/python -c "import whisper, torch; print('whisper runtime ok without build tooling')" \
 && chmod -R a+rX /opt/cache /opt/whisper

RUN groupadd -r -g 10001 worker && useradd -r -u 10001 -g worker -m -d /home/worker worker \
 && mkdir -p /var/lib/fdm-worker && chown worker:worker /var/lib/fdm-worker

WORKDIR /app
COPY --from=build /build/target/worker-agent-*.jar /app/worker-agent.jar
COPY docker-entrypoint.sh /app/docker-entrypoint.sh
RUN chmod 0555 /app/docker-entrypoint.sh

ENV TRANSCRIPTION_RUNTIME=WHISPER_CLI \
    TRANSCRIPTION_COMMAND=/opt/whisper/bin/whisper \
    TRANSCRIPTION_MODEL=${WHISPER_MODEL} \
    FFMPEG_PATH=ffmpeg \
    FFPROBE_PATH=ffprobe \
    FDM_WORKER_ID_FILE=/var/lib/fdm-worker/installation-id

USER worker
ENTRYPOINT ["/app/docker-entrypoint.sh"]
