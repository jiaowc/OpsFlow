package com.opsflow.spi;

import com.opsflow.integration.notify.NotifyMessage;

import java.util.List;

/**
 * 流水线通知的外部通道。核心负责组装消息和发站内信，外部推送（群机器人等）由实现提供。
 * <p>核心默认实现不推送；{@code notify} 步骤返回"社区版不含外部通知通道"。</p>
 */
public interface JobNotifyExtension {

    /**
     * 补充消息内容（如关联的上线任务、门户链接）。在核心写完"任务编号"字段之后、"触发人"之前调用，
     * 站内信和外部通道都会带上补充的内容。
     */
    default void enrich(JobNotifyContext context, NotifyMessage message) {
    }

    /** Job 结束（SUCCESS / FAILED）后推送外部通道；在通知线程里调用，异常由核心捕获 */
    void onJobFinished(JobNotifyContext context, NotifyMessage message);

    /**
     * 流水线 {@code notify} 步骤：把自定义消息同步发到外部通道。
     *
     * @return 写进步骤日志的结果说明
     */
    String sendStepMessage(JobNotifyContext context, NotifyMessage message);

    /**
     * 流水线 {@code notify} 步骤，带步骤里指定的通道 id。
     * <p>{@code channelIds} 为空表示没指定，按实现自己的规则匹配；默认实现忽略指定的 id，转调
     * {@link #sendStepMessage(JobNotifyContext, NotifyMessage)}，不支持指定通道的实现无需改动。</p>
     *
     * @param channelIds 步骤参数 {@code channelIds} 解析出的 id，不为 null
     * @return 写进步骤日志的结果说明
     */
    default String sendStepMessage(JobNotifyContext context, NotifyMessage message, List<Long> channelIds) {
        return sendStepMessage(context, message);
    }
}
