ARG REGISTRY=docker.io/library
FROM ${REGISTRY}/golang:1.23-alpine AS build
WORKDIR /src
COPY go.mod .
COPY main.go .
RUN CGO_ENABLED=0 go build -o /parser .

FROM ${REGISTRY}/alpine:3.20
COPY --from=build /parser /parser
ENV PORT=9092
EXPOSE 9092
USER 1000
ENTRYPOINT ["/parser"]
