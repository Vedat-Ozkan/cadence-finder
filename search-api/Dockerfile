FROM eclipse-temurin:25-jre
COPY build/libs/search-api.jar /app/app.jar
ADD --chmod=644 --checksum=sha256:b752cf672a963b1da316fb87a1d4f32ffde83ec2c63f30ade4bc3366b47a8a06 https://repo1.maven.org/maven2/software/amazon/opentelemetry/aws-opentelemetry-agent/2.32.0/aws-opentelemetry-agent-2.32.0.jar /app/aws-opentelemetry-agent.jar
USER 10001
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
