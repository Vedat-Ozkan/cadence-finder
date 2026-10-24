FROM eclipse-temurin:25-jre
COPY --from=public.ecr.aws/aws-observability/adot-autoinstrumentation-java:v2.32.0 /javaagent.jar /app/aws-opentelemetry-agent.jar
COPY build/libs/search-api.jar /app/app.jar
USER 10001
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
