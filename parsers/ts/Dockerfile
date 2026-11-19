ARG REGISTRY=docker.io/library
FROM ${REGISTRY}/node:20-slim
WORKDIR /app
COPY package.json .
RUN npm install --omit=dev --no-audit --no-fund
COPY parser_server.js .
ENV PORT=9093
EXPOSE 9093
USER 1000
CMD ["node", "parser_server.js"]
