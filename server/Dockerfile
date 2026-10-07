FROM node:22-bookworm-slim
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends python3 make g++ ca-certificates && rm -rf /var/lib/apt/lists/*
COPY package*.json ./
RUN npm install --omit=dev
COPY server.mjs ./
RUN mkdir -p /data
ENV NODE_ENV=production DATA_DIR=/data PORT=8787
EXPOSE 8787
CMD ["npm","start"]
