# syntax=docker/dockerfile:1

# ---- build stage: JDK + Maven, compiles the jar ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# copy the pom first and download dependencies in their own layer, so changing a .java file
# doesn't download everything again. The cache mount keeps ~/.m2 between builds too.
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp dependency:go-offline

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp package -DskipTests

# split the fat jar into layers (libraries change rarely, my classes change every build)
RUN java -Djarmode=tools -jar target/webhook-delivery-service-*.jar extract --layers --launcher --destination target/extracted

# ---- runtime stage: only a JRE, no Maven, no source code ----
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app

COPY --from=build /app/target/extracted/dependencies/ ./
COPY --from=build /app/target/extracted/spring-boot-loader/ ./
COPY --from=build /app/target/extracted/snapshot-dependencies/ ./
COPY --from=build /app/target/extracted/application/ ./

# don't run as root inside the container
USER app
EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health/readiness || exit 1

# MaxRAMPercentage: use 75% of the container's memory limit for the heap
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "org.springframework.boot.loader.launch.JarLauncher"]
