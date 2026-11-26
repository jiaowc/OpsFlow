package com.opsflow.api.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 模版变量检查结果：模版里写的 ${变量} 哪些能被渲染、哪些有问题。
 */
@Data
public class TemplateVariableCheckDTO {

    /**
     * dockerfile / deployment / service
     */
    private String type;

    /**
     * 没有发现问题时为 true
     */
    private boolean passed;

    /**
     * 有问题的写法（不会被替换的占位符）
     */
    private List<Issue> issues = new ArrayList<>();

    /**
     * 模版里用到且平台支持的变量
     */
    private List<UsedVariable> used = new ArrayList<>();

    /**
     * 未检查、渲染时原样保留的写法（Dockerfile 里声明的 ARG/ENV、shell 环境变量等）
     */
    private List<String> passthrough = new ArrayList<>();

    /**
     * 该类型模版支持的全部变量
     */
    private List<Variable> supported = new ArrayList<>();

    @Data
    public static class Issue {
        /**
         * 模版中的原文，例如 ${serviceNmae}
         */
        private String placeholder;

        /**
         * 出现的行号（从 1 开始）
         */
        private List<Integer> lines = new ArrayList<>();

        private String message;

        /**
         * 建议改成的变量名（不含 ${}），没有合适建议时为空
         */
        private String suggestion;
    }

    @Data
    public static class Variable {
        private String name;

        private String description;

        /**
         * 该变量有值的前提条件；始终有值时为空
         */
        private String condition;
    }

    @Data
    public static class UsedVariable {
        private String name;

        private String description;

        private String condition;

        private List<Integer> lines = new ArrayList<>();
    }
}
