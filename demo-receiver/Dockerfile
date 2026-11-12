# same two base images as the main app, so nothing extra is downloaded
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY DemoReceiver.java .
RUN javac -d out DemoReceiver.java && jar --create --file receiver.jar --main-class DemoReceiver -C out .

FROM eclipse-temurin:21-jre-alpine
COPY --from=build /src/receiver.jar /app/receiver.jar
USER nobody
EXPOSE 9090
ENTRYPOINT ["java", "-jar", "/app/receiver.jar"]
