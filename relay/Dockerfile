# SwitchTether relay: static Go binary on distroless, running as non-root.
# Build context is this directory (relay/):
#   docker build -t switchtether-relay relay/
# Cloudflare Containers builds the same file (relay/cloudflare/wrangler.jsonc).

ARG GO_IMAGE=golang:1.27.3-alpine

FROM --platform=$BUILDPLATFORM ${GO_IMAGE} AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY cmd ./cmd
COPY internal ./internal
ARG TARGETOS=linux
ARG TARGETARCH=amd64
ARG VERSION=dev
RUN CGO_ENABLED=0 GOOS=$TARGETOS GOARCH=$TARGETARCH \
    go build -trimpath -ldflags "-s -w -X main.version=${VERSION}" \
    -o /out/switchtether-relay ./cmd/switchtether-relay

FROM gcr.io/distroless/static-debian13:nonroot
COPY --from=build /out/switchtether-relay /switchtether-relay
USER nonroot:nonroot
# 47700: raw TCP transport (tcp://host:47700)
# 8080:  HTTP — WebSocket at /v1/tunnel and GET /healthz
EXPOSE 47700 8080
# The binary probes its own /healthz; no shell or curl needed.
HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 \
    CMD ["/switchtether-relay", "-healthcheck"]
ENTRYPOINT ["/switchtether-relay"]
