package com.opsflow.spi;

import java.util.Map;

/**
 * 系统配置的扩展：特定配置类型的写入校验、写入前的值转换（如加密敏感项）、读取时的值转换（如脱敏）、
 * 写入后处理和通知选项。可以有多个实现，每个实现只处理自己负责的配置类型，其他类型原样返回。
 */
public interface SystemConfigExtension {

    /** 写入某配置类型之前调用；不允许写入时抛 {@link com.opsflow.common.exception.BusinessException} */
    default void beforeSave(String configType) {
    }

    /**
     * 通用配置接口写入每一项之前调用，返回实际存库的值。多个实现依次调用，前一个的返回值作为后一个的 {@code value}。
     * 默认原样返回。
     *
     * @param configType  配置类型（已去掉首尾空白）
     * @param configKey   配置项（已去掉首尾空白）
     * @param value       本次提交的值
     * @param storedValue 库里已有的值，没有时为 null
     */
    default String beforeStore(String configType, String configKey, String value, String storedValue) {
        return value;
    }

    /**
     * 通用配置接口返回每一项之前调用（列表、按类型查询、保存后的回显），返回给前端看到的值。
     * 默认原样返回。只影响接口输出，不影响库里的值和 {@code SystemConfigService} 的读取。
     */
    default String maskForRead(String configType, String configKey, String storedValue) {
        return storedValue;
    }

    /** 写入之后调用（如让缓存的 token 失效） */
    default void afterSave(String configType) {
    }

    /** 往 {@code GET /api/system/notify/options} 的结果里补充扩展通道的选项 */
    default void contributeNotifyOptions(Map<String, Object> options) {
    }
}
