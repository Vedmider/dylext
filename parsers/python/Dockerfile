ARG REGISTRY=docker.io/library
FROM ${REGISTRY}/python:3.12-slim
WORKDIR /app
COPY parser_server.py .
ENV PORT=9091
EXPOSE 9091
USER 1000
CMD ["python3", "parser_server.py"]
