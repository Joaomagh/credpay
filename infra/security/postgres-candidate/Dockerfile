FROM postgres:17.12-alpine3.24@sha256:a77aa70e641402a21411b92b721c58d10f23b50ae5fdd2333efd22d166e1e5f7

# The official entrypoint invokes `gosu postgres` only while dropping root.
# Keep that interface, but use Alpine's small C implementation instead of the
# Go binary whose stdlib findings remain open in the official image.
RUN apk add --no-cache su-exec=0.3-r0 \
    && rm /usr/local/bin/gosu \
    && ln -s /sbin/su-exec /usr/local/bin/gosu \
    && gosu postgres true
