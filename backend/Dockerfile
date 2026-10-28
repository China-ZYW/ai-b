# 面向 4核4G 的轻量运行镜像：JRE + 分层 jar
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# 构建产物（pom 中 finalName=aibi）
COPY target/aibi.jar app.jar

# 时区与编码
ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-Xmx768m -Xms256m -XX:MaxMetaspaceSize=192m -XX:+UseSerialGC"

EXPOSE 8080

# 健康检查（依赖 actuator/health）
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD wget -qO- http://127.0.0.1:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
