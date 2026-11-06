FROM --platform=$BUILDPLATFORM golang:1.27.3-alpine AS builder
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
ARG TARGETOS=linux
ARG TARGETARCH
RUN CGO_ENABLED=0 GOOS=$TARGETOS GOARCH=$TARGETARCH go build -trimpath -ldflags="-s -w" -o /obd-blackbox ./cmd/obd-blackbox

FROM alpine:3.22
RUN apk add --no-cache ca-certificates tzdata && adduser -D -u 65532 obd-blackbox
COPY --from=builder /obd-blackbox /usr/local/bin/obd-blackbox
USER 65532:65532
EXPOSE 8080
ENTRYPOINT ["obd-blackbox"]
