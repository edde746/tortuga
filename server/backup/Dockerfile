# Scheduled, client-side encrypted Postgres backups to S3-compatible storage. The base image's
# pg_dump/pg_restore match the server's Postgres major version; age encrypts, rclone talks S3, supercronic schedules.
FROM postgres:18-alpine

ARG TARGETARCH
ARG SUPERCRONIC_VERSION=v0.2.49
ARG SUPERCRONIC_SHA256_AMD64=a53ae236602c7338aba3fbaff40bda6300eae3b9fedb8261eb06cfe3724430c1
ARG SUPERCRONIC_SHA256_ARM64=02aa0cb229ba09050cba6638059dadb9eedc2276632ea43d6a57a2f8c1629dd5

RUN apk add --no-cache age rclone ca-certificates \
    && case "$TARGETARCH" in \
         amd64) sha="$SUPERCRONIC_SHA256_AMD64" ;; \
         arm64) sha="$SUPERCRONIC_SHA256_ARM64" ;; \
         *) echo "unsupported architecture: $TARGETARCH" >&2; exit 1 ;; \
       esac \
    && wget -q -O /usr/local/bin/supercronic \
         "https://github.com/aptible/supercronic/releases/download/${SUPERCRONIC_VERSION}/supercronic-linux-${TARGETARCH}" \
    && echo "$sha  /usr/local/bin/supercronic" | sha256sum -c - \
    && chmod 0755 /usr/local/bin/supercronic

COPY tortuga-backup.sh /usr/local/bin/tortuga-backup
RUN chmod 0755 /usr/local/bin/tortuga-backup

# The postgres user of the base image; nothing here needs root.
USER postgres
WORKDIR /tmp
ENTRYPOINT ["/usr/local/bin/tortuga-backup"]
CMD ["schedule"]
