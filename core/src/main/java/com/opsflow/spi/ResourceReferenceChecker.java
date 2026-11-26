package com.opsflow.spi;

/**
 * 删除核心资源前，检查扩展自己的数据是否还引用它。可以有多个实现。
 * <p>核心先检查自己的表（服务、构建记录），再逐个调用实现；被引用时由实现抛出
 * {@link com.opsflow.common.exception.BusinessException}，消息直接展示给用户。</p>
 */
public interface ResourceReferenceChecker {

    /** 项目，id 为 project.id */
    String PROJECT = "project";
    /** 流水线模版，id 为 pipeline.id */
    String PIPELINE_TEMPLATE = "pipeline_template";

    void assertDeletable(String resourceType, Long resourceId);
}
