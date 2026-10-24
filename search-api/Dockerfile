FROM eclipse-temurin:25-jre
COPY build/libs/search-api.jar /app/app.jar
USER 10001
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
