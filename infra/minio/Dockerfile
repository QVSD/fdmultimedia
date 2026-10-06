# MinIO is no longer published to Docker Hub by its maintainers, so the release is built from the tagged upstream source. This keeps a
# clean checkout buildable and the exact server and client versions pinned (MinIO is AGPL-3.0; see docs/RELEASE licenses).
FROM golang:1.25-alpine AS build
ARG MINIO_RELEASE=RELEASE.2025-10-15T17-29-55Z
ARG MC_RELEASE=RELEASE.2025-08-13T08-35-41Z
ENV CGO_ENABLED=0 GOFLAGS=-trimpath
RUN apk add --no-cache git
RUN go install github.com/minio/minio@${MINIO_RELEASE} && go install github.com/minio/mc@${MC_RELEASE}

FROM alpine:3.22
ARG MINIO_RELEASE=RELEASE.2025-10-15T17-29-55Z
LABEL org.opencontainers.image.title="fdmultimedia-minio" org.opencontainers.image.version="${MINIO_RELEASE}"
RUN apk add --no-cache curl ca-certificates && addgroup -S -g 10001 minio && adduser -S -u 10001 -G minio minio \
 && mkdir /data && chown minio:minio /data
COPY --from=build /go/bin/minio /usr/local/bin/minio
COPY --from=build /go/bin/mc /usr/local/bin/mc
USER minio
VOLUME ["/data"]
EXPOSE 9000
ENTRYPOINT ["/usr/local/bin/minio"]
CMD ["server", "/data"]
