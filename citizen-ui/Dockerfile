FROM node:24-bookworm-slim AS build
WORKDIR /app/citizen-ui
COPY citizen-ui/package*.json ./
RUN npm ci
COPY citizen-ui/ ./
RUN npm run build && npm prune --omit=dev

FROM node:24-bookworm-slim
ENV NODE_ENV=production PORT=3000
WORKDIR /app/citizen-ui
COPY --from=build --chown=node:node /app/citizen-ui ./
COPY --chown=node:node seed /app/seed
USER node
EXPOSE 3000
CMD ["npm", "start"]
