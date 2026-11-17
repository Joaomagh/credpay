FROM postgres:17.12-alpine3.24@sha256:a77aa70e641402a21411b92b721c58d10f23b50ae5fdd2333efd22d166e1e5f7 AS prepared

# The official entrypoint invokes `gosu postgres` only while dropping root.
# Keep that interface, but use Alpine's small C implementation instead of the
# Go binary whose stdlib findings remain open in the official image.
RUN apk add --no-cache su-exec=0.3-r0 \
    && rm /usr/local/bin/gosu \
    && ln -s /sbin/su-exec /usr/local/bin/gosu \
    && gosu postgres true

# An upper-layer deletion leaves the original Go binary in the distributable
# base-layer blob. Copy the resolved filesystem into one final layer so the
# candidate scan covers the bytes that would actually be published.
FROM scratch
COPY --from=prepared / /

# Runtime contract from the pinned official Alpine Dockerfile; build-only
# variables are intentionally omitted. The probe verifies init and restart.
ENV PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    LANG=en_US.utf8 \
    PG_MAJOR=17 \
    PG_VERSION=17.12 \
    PGDATA=/var/lib/postgresql/data
VOLUME /var/lib/postgresql/data
USER postgres
ENTRYPOINT ["docker-entrypoint.sh"]
STOPSIGNAL SIGINT
EXPOSE 5432
CMD ["postgres"]
