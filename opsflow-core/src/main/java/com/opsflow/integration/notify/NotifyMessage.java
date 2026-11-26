package com.opsflow.integration.notify;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 与通道无关的通知内容，由各通知通道（如企业版的外部通知）转成自己的消息格式。
 */
@Data
public class NotifyMessage {

    /** 标题 */
    private String title;

    /** 结果：true 成功 / false 失败 / null 中性（测试消息、流水线里的自定义通知） */
    private Boolean success;

    /** 按顺序展示的 标签 → 值 */
    private Map<String, String> fields = new LinkedHashMap<>();

    /** 附加正文（失败原因、自定义内容），可空 */
    private String detail;

    /** 跳转地址，可空 */
    private String linkUrl;

    public NotifyMessage field(String label, String value) {
        if (value != null && !value.trim().isEmpty()) {
            fields.put(label, value.trim());
        }
        return this;
    }
}
