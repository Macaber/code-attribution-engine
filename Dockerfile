# --- Stage 1: Build ---
FROM node:20-alpine AS builder

WORKDIR /app

# Install dependencies first (for better caching)
COPY package*.json ./
RUN npm install

# Copy source and config files
COPY tsconfig.json tsconfig.build.json ./
COPY src ./src

# Build the project
RUN npm run build

# --- Stage 2: Runtime ---
FROM node:20-alpine

WORKDIR /app

# Copy only production dependencies and built code
COPY package*.json ./
RUN npm install --omit=dev

COPY --from=builder /app/dist ./dist

# Set environment defaults
ENV NODE_ENV=production
ENV PORT=3000

EXPOSE 3000

# Start the application
CMD ["node", "dist/main.js"]
