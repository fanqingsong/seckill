FROM swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/maven:3.8.8-eclipse-temurin-8 AS builder
WORKDIR /build
COPY docker/maven-settings.xml /root/.m2/settings.xml
COPY . .
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -B -N install -DskipTests && \
    mvn -B install -DskipTests -pl docker-build-config && \
    mvn -B package -DskipTests \
      -pl seckill-admin-service,seckill-command-service,seckill-query-service,seckill-event-service \
      -am

FROM swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/eclipse-temurin:8-jre AS admin-service
WORKDIR /app
COPY --from=builder /build/seckill-admin-service/target/seckill/seckill-admin-service-0.2.0-SNAPSHOT-exec.jar app.jar
EXPOSE 8081
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]

FROM swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/eclipse-temurin:8-jre AS command-service
WORKDIR /app
COPY --from=builder /build/seckill-command-service/target/seckill/seckill-command-service-0.2.0-SNAPSHOT-exec.jar app.jar
EXPOSE 8082
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]

FROM swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/eclipse-temurin:8-jre AS query-service
WORKDIR /app
COPY --from=builder /build/seckill-query-service/target/seckill/seckill-query-service-0.2.0-SNAPSHOT-exec.jar app.jar
EXPOSE 8083
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]

FROM swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/eclipse-temurin:8-jre AS event-service
WORKDIR /app
COPY --from=builder /build/seckill-event-service/target/seckill/seckill-event-service-0.2.0-SNAPSHOT-exec.jar app.jar
EXPOSE 8084
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
