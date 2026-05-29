# Stage 1: build then compile Java code using JDK
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
COPY src/Main.java .
RUN javac Main.java

# Stage 2: Run — with JRE only, copy the compiled .class files from the build stage
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/*.class .
ENTRYPOINT ["java", "Main"]