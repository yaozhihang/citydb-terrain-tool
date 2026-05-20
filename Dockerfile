# Stage 1: Build the Java application
FROM eclipse-temurin:21-jdk AS builder

WORKDIR /app
COPY gradlew gradlew.bat settings.gradle build.gradle ./
COPY gradle/ gradle/
COPY src/ src/

RUN chmod +x gradlew && ./gradlew installDist --no-daemon

# Stage 2: Minimal runtime image
FROM eclipse-temurin:21-jre

WORKDIR /app

# Copy built distribution from builder stage
COPY --from=builder /app/build/install/citydb-terrain-tool/ ./

ENTRYPOINT ["./bin/citydb-terrain-tool"]
CMD ["--help"]
