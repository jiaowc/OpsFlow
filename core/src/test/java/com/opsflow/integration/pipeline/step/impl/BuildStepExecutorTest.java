package com.opsflow.integration.pipeline.step.impl;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildStepExecutorTest {

    private static Map<String, String> vars(String... kv) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put(kv[i], kv[i + 1]);
        }
        return map;
    }

    @Test
    void replacesPlatformVariables() {
        String rendered = BuildStepExecutor.renderBuildCommand(
                "npm install && npm run build:${env}", vars("env", "prod"));
        assertEquals("npm install && npm run build:prod", rendered);
    }

    @Test
    void replacesEveryOccurrence() {
        String rendered = BuildStepExecutor.renderBuildCommand(
                "echo ${env} && mvn package -P${env} -Drevision=${branch}",
                vars("env", "test", "branch", "release/1.2.0"));
        assertEquals("echo test && mvn package -Ptest -Drevision=release/1.2.0", rendered);
    }

    @Test
    void keepsUnknownAndShellStylePlaceholders() {
        String command = "export PATH=${JAVA_HOME}/bin:$PATH && echo ${1} ${OPTS:-none} && mvn -P${env} package";
        String rendered = BuildStepExecutor.renderBuildCommand(command, vars("env", "dev"));
        assertEquals("export PATH=${JAVA_HOME}/bin:$PATH && echo ${1} ${OPTS:-none} && mvn -Pdev package", rendered);
    }

    @Test
    void commandWithoutPlaceholdersIsUnchanged() {
        String command = "mvn clean package -DskipTests";
        assertEquals(command, BuildStepExecutor.renderBuildCommand(command, vars("env", "dev")));
    }

    @Test
    void valueWithDollarOrBackslashIsNotTreatedAsRegexReplacement() {
        // 取值合规但未被使用的变量即使含特殊字符也不影响渲染
        String rendered = BuildStepExecutor.renderBuildCommand(
                "npm run build:${env}", vars("env", "stage", "service", "若依 $1 \\ 前端"));
        assertEquals("npm run build:stage", rendered);
    }

    @Test
    void rejectsValueThatWouldChangeTheCommand() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> BuildStepExecutor.renderBuildCommand(
                        "npm run build:${branch}", vars("branch", "x; rm -rf ~")));
        assertTrue(e.getMessage().contains("${branch}"));

        assertThrows(IllegalArgumentException.class,
                () -> BuildStepExecutor.renderBuildCommand("echo ${branch}", vars("branch", "$(id)")));
        assertThrows(IllegalArgumentException.class,
                () -> BuildStepExecutor.renderBuildCommand("echo ${service}", vars("service", "若依 前端")));
        assertThrows(IllegalArgumentException.class,
                () -> BuildStepExecutor.renderBuildCommand("echo ${env}", vars("env", "")));
    }

    @Test
    void nullOrEmptyInputsAreReturnedAsIs() {
        assertEquals(null, BuildStepExecutor.renderBuildCommand(null, vars("env", "dev")));
        assertEquals("", BuildStepExecutor.renderBuildCommand("", vars("env", "dev")));
        assertEquals("npm run build:${env}", BuildStepExecutor.renderBuildCommand("npm run build:${env}", null));
    }
}
