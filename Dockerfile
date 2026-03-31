# 使用 Eclipse Temurin 提供的稳定 JRE 11 镜像
# 这也是目前 Docker 官方推荐替代旧 openjdk 镜像的方案之一
FROM eclipse-temurin:11-jre-focal

# 安装 Graphviz (PlantUML 渲染图表依赖)
RUN apt-get update && \
    apt-get install -y graphviz && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# 设置工作目录
WORKDIR /app

# 从当前目录（由 deploy.ps1 准备的暂存区）复制 Jar 包
# 这里的 app.jar 是 deploy.ps1 在打包时重命名后的文件
COPY app.jar app.jar

# 暴露应用端口（需与 compose.yml 中的端口一致）
EXPOSE 8080

# 设置 JVM 参数优化
ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC"

# 启动应用
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
