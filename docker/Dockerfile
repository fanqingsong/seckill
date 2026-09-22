FROM swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/maven:3.9.9-eclipse-temurin-17 AS builder
WORKDIR /build
COPY docker/maven-settings.xml /root/.m2/settings.xml
COPY . .
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -B -N install -DskipTests && \
    mvn -B install -DskipTests -pl docker-build-config && \
    mvn -B package -DskipTests \
      -pl service/seckill-admin-service,service/seckill-command-service,service/seckill-query-service,service/seckill-event-service,service/seckill-gateway \
      -am

FROM swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/eclipse-temurin:17-jre AS admin-service
WORKDIR /app
COPY --from=builder /build/service/seckill-admin-service/target/seckill/seckill-admin-service-0.2.0-SNAPSHOT-exec.jar app.jar
EXPOSE 8081
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]

FROM swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/eclipse-temurin:17-jre AS command-service
WORKDIR /app
COPY --from=builder /build/service/seckill-command-service/target/seckill/seckill-command-service-0.2.0-SNAPSHOT-exec.jar app.jar
EXPOSE 8082
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]

FROM swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/eclipse-temurin:17-jre AS query-service
WORKDIR /app
COPY --from=builder /build/service/seckill-query-service/target/seckill/seckill-query-service-0.2.0-SNAPSHOT-exec.jar app.jar
EXPOSE 8083
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]

FROM swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/eclipse-temurin:17-jre AS event-service
WORKDIR /app
COPY --from=builder /build/service/seckill-event-service/target/seckill/seckill-event-service-0.2.0-SNAPSHOT-exec.jar app.jar
EXPOSE 8084
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]

FROM swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/eclipse-temurin:17-jre AS gateway
WORKDIR /app
COPY --from=builder /build/service/seckill-gateway/target/seckill/seckill-gateway-0.2.0-SNAPSHOT-exec.jar app.jar
EXPOSE 8085
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
