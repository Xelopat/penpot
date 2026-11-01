# syntax=docker/dockerfile:1
FROM --platform=$BUILDPLATFORM penpotapp/devenv:latest AS build
WORKDIR /workspace
COPY . .
ARG KORDEX_VERSION=2.18.5-kordex.1
ENV JAVA_OPTS="-Xmx4g" NODE_OPTIONS="--max-old-space-size=4096"
RUN bash docker/kordex/build.sh "$KORDEX_VERSION"

FROM penpotapp/frontend:2.18.5
COPY --from=build --chown=1001:0 /workspace/frontend/target/dist/ /var/www/app/
COPY --from=build --chown=1001:0 /workspace/kordex-csp-script-hashes.txt /etc/nginx/csp-script-hashes.txt
ARG KORDEX_VERSION=2.18.5-kordex.1
LABEL org.opencontainers.image.source="https://github.com/Xelopat/penpot" \
      org.opencontainers.image.version=$KORDEX_VERSION
