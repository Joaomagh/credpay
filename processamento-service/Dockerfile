FROM eclipse-temurin:21.0.13_9-jre-ubi10-minimal@sha256:6be4b394b6e718bcebce12a579576ea82f9a5bffed0e7bd7445ea1ba416c260a

WORKDIR /app
COPY target/processamento-service-0.0.1-SNAPSHOT.jar /app/app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
CMD []
