USE opsflow;
SET NAMES utf8mb4;

UPDATE `pipeline_template` SET
  `description` = '通用 Java 运行镜像：构建产物约定为 target/app.jar（单模块/多模块均可）。默认 JDK8 多架构；JDK11/17 改 FROM 即可',
  `base_image` = 'eclipse-temurin:8-jre',
  `content` = 'FROM eclipse-temurin:8-jre

WORKDIR /app

ENV TZ=Asia/Shanghai \\
    JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"

# 由构建步骤规范产出，避免 COPY target/*.jar 在多模块/附带 sources 时踩坑
COPY target/app.jar app.jar

EXPOSE ${servicePort}

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
',
  `update_time` = NOW()
WHERE `id` = 1 OR (`type` = 'dockerfile' AND `name` = 'java镜像构建模版');

UPDATE `pipeline_step_def` SET
  `description` = 'Maven 构建，并规范产出 target/app.jar（兼容单模块/多模块）',
  `content_config` = '{"buildCommand": "set -e; mvn clean package -DskipTests; mkdir -p target; JAR=$(find . -type f -path ''*/target/*.jar'' ! -name ''*-sources.jar'' ! -name ''*-javadoc.jar'' ! -name ''*-tests.jar'' ! -name ''*-test-sources.jar'' ! -name ''original-*.jar'' -printf ''%s\\\\t%p\\\\n'' | sort -nr | head -1 | cut -f2-); test -n \\"$JAR\\"; cp -f \\"$JAR\\" target/app.jar; echo \\"选用 jar: $JAR -> target/app.jar\\"; ls -lh target/app.jar"}',
  `timeout_seconds` = 1800,
  `update_time` = NOW()
WHERE `id` = 2 OR (`step_type` = 'build' AND `phase` = 'ci' AND `name` IN ('后端构建', '代码构建'));

UPDATE `pipeline_step_def` SET
  `description` = '通用 Java 镜像构建（依赖 target/app.jar）',
  `content_config` = '{"dockerfileTemplateId": "1", "dockerfilePath": "Dockerfile", "dockerfileContent": "FROM eclipse-temurin:8-jre\\n\\nWORKDIR /app\\n\\nENV TZ=Asia/Shanghai \\\\\\n    JAVA_OPTS=\\"-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0\\"\\n\\n# 由构建步骤规范产出，避免 COPY target/*.jar 在多模块/附带 sources 时踩坑\\nCOPY target/app.jar app.jar\\n\\nEXPOSE ${servicePort}\\n\\nENTRYPOINT [\\"sh\\", \\"-c\\", \\"exec java $JAVA_OPTS -jar /app/app.jar\\"]\\n", "dockerBuildCommand": "docker build -f ${dockerfilePath} -t ${harbor}/${namespace}/${serviceName}:${tag} ${workspace}"}',
  `timeout_seconds` = 600,
  `update_time` = NOW()
WHERE `id` = 3 OR (`step_type` = 'docker_build' AND `name` IN ('后端镜像制作', '镜像制作'));

