# Build from the REPOSITORY ROOT so the shared/ registry is included:
#   docker build -f backend/Dockerfile -t talayar-gateway .
FROM node:22-slim

WORKDIR /app
ENV NODE_ENV=production

COPY backend/package.json backend/package-lock.json ./
RUN npm ci --omit=dev && npm cache clean --force

COPY backend/src ./src
COPY shared /shared

RUN mkdir -p /app/data

EXPOSE 8080

CMD ["npm", "start"]
