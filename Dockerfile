# --- Stage 1: Build ---
FROM maven:3.8.5-openjdk-17 AS builder

WORKDIR /app

# Copy pom.xml and download dependencies (for better caching)
COPY pom.xml ./
RUN mvn dependency:go-offline -B

# Copy source code
COPY src ./src

# Package the application
RUN mvn package -DskipTests -B

# --- Stage 2: Runtime ---
FROM openjdk:17-slim

WORKDIR /app

# Copy the built jar from the builder stage
COPY --from=builder /app/target/*.jar app.jar

# Set environment defaults
ENV PORT=3000

EXPOSE 3000

# Start the application
ENTRYPOINT ["java", "-jar", "app.jar"]
