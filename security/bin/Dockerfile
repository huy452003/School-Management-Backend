# Multi-stage build for Spring Boot application
FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /app

# Copy pom files first for better caching
COPY pom.xml .
COPY */pom.xml ./

# Download dependencies (this layer will be cached if pom.xml doesn't change)
RUN mvn dependency:go-offline -B -Pproduction || true

# Copy source code (excluding target directories via .dockerignore)
COPY . .

# Remove problematic files and test directories before build
RUN find . -name "*.drawio" -type f -delete 2>/dev/null || true && \
    find . -name "*.drawio.xml" -type f -delete 2>/dev/null || true && \
    find . -type d -name "test" -path "*/src/test" -exec rm -rf {} + 2>/dev/null || true

# Build application (skip tests, use production profile)
RUN mvn clean install -DskipTests -Dmaven.test.skip=true -Pproduction

# Runtime stage
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# Copy only the built JAR from build stage
COPY --from=build /app/security/target/security-3.5.0-exec.jar app.jar

# Expose port - Railway will automatically set PORT env variable
# EXPOSE 8083 is just metadata, Railway will map the actual PORT automatically
EXPOSE 8083

# Run the application - Railway automatically sets PORT env variable
# Application will use PORT from Railway, fallback to 8083 if not set (matches application.properties)
CMD java -Dserver.port=${PORT:-8083} -jar app.jar

