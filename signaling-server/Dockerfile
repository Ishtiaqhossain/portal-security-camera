# Build context must be the REPO ROOT (so web-client/ is available alongside
# signaling-server/). See deploy/docker-compose.yml.
FROM node:20-alpine

WORKDIR /app

# Install deps first for layer caching.
COPY signaling-server/package*.json ./signaling-server/
RUN cd signaling-server && npm ci --omit=dev

# App + static web client.
COPY signaling-server ./signaling-server
COPY web-client ./web-client

WORKDIR /app/signaling-server
EXPOSE 8080

# Config comes from the environment (compose), not a baked-in .env.
CMD ["node", "server.js"]
