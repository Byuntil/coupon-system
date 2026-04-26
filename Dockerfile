# Build stage
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app

# Copy Gradle files
COPY gradlew .
COPY gradle gradle
COPY build.gradle .
COPY settings.gradle .

# Copy source code (multi-module)
COPY coupon-common coupon-common
COPY coupon-api coupon-api
COPY coupon-consumer coupon-consumer

# Give execute permission to gradlew
RUN chmod +x ./gradlew

# Build coupon-api (Phase 1 대상 모듈)
RUN ./gradlew :coupon-api:bootJar -x test

# Runtime stage
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Install required packages
RUN apk add --no-cache tzdata
ENV TZ=Asia/Seoul

# Create non-root user
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

# Copy built artifact from builder stage
COPY --from=builder /app/coupon-api/build/libs/*-SNAPSHOT.jar app.jar

# Set environment variables
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"

# Health check
HEALTHCHECK --interval=30s --timeout=3s \
  CMD wget --quiet --tries=1 --spider http://localhost:8080/actuator/health || exit 1

# Run the application
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]