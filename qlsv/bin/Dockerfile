# Multi-stage build for QLSV Spring Boot application
# This Dockerfile must be built from the project root directory
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
# Build entire project to ensure all dependencies are built (same as Security service)
RUN mvn clean install -DskipTests -Dmaven.test.skip=true -Pproduction

# Verify and prepare executable WAR/JAR file for copying
# With packaging=war and classifier=exec, spring-boot-maven-plugin creates -exec.war (executable WAR)
RUN echo "=== Listing all files in qlsv/target ===" && \
    ls -la /app/qlsv/target/ && \
    echo "=== Finding executable WAR/JAR files (-exec.war or -exec.jar) ===" && \
    find /app/qlsv/target -name "*-exec.*" -type f && \
    echo "=== Copying executable WAR/JAR ===" && \
    if [ -f /app/qlsv/target/QLSV-3.5.0-exec.war ]; then \
        echo "Found QLSV-3.5.0-exec.war, copying as app.jar"; \
        cp /app/qlsv/target/QLSV-3.5.0-exec.war /app/qlsv/target/app.jar && \
        echo "Verifying app.jar has Main-Class..." && \
        unzip -p /app/qlsv/target/app.jar META-INF/MANIFEST.MF | grep -i "Main-Class" || echo "WARNING: No Main-Class found in manifest"; \
    elif [ -f /app/qlsv/target/QLSV-3.5.0-exec.jar ]; then \
        echo "Found QLSV-3.5.0-exec.jar, copying as app.jar"; \
        cp /app/qlsv/target/QLSV-3.5.0-exec.jar /app/qlsv/target/app.jar && \
        echo "Verifying app.jar has Main-Class..." && \
        unzip -p /app/qlsv/target/app.jar META-INF/MANIFEST.MF | grep -i "Main-Class" || echo "WARNING: No Main-Class found in manifest"; \
    else \
        echo "ERROR: QLSV-3.5.0-exec.war or QLSV-3.5.0-exec.jar not found!"; \
        echo "Trying to find any -exec file..."; \
        EXEC_FILE=$(find /app/qlsv/target -name "*-exec.*" -type f | head -1); \
        if [ -n "$EXEC_FILE" ]; then \
            echo "Found executable file: $EXEC_FILE"; \
            cp "$EXEC_FILE" /app/qlsv/target/app.jar; \
        else \
            echo "ERROR: No executable file (-exec.war or -exec.jar) found!"; \
            exit 1; \
        fi; \
    fi && \
    ls -la /app/qlsv/target/app.jar

# Runtime stage
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# Copy the prepared JAR file
COPY --from=build /app/qlsv/target/app.jar app.jar

# Expose port - Railway will automatically set PORT env variable
# EXPOSE 8080 is just metadata, Railway will map the actual PORT automatically
EXPOSE 8080

# Run the application - Railway automatically sets PORT env variable
# Application will use PORT from Railway, fallback to 8080 if not set (matches application.properties)
CMD java -Dserver.port=${PORT:-8080} -jar app.jar

