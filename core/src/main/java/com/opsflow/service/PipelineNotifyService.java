package com.opsflow.service;

import com.opsflow.dao.model.BuildJob;

/**
 * 流水线结果通知：站内信发给触发人；外部通道由 {@link com.opsflow.spi.JobNotifyExtension} 提供。
 */
public interface PipelineNotifyService {

    /**
     * 构建任务到达终态（SUCCESS / FAILED）后调用。异步发送，立即返回，任何失败都不会抛给调用方。
     *
     * @param job 已写好终态、错误信息、结束时间的构建任务
     */
    void notifyJobFinished(BuildJob job);

    /**
     * 流水线里的「通知」步骤：把自定义内容同步交给外部通道扩展发送。
     *
     * @return 给步骤日志用的结果说明
     */
    default String sendStepMessage(Long buildJobId, String message) {
        return sendStepMessage(buildJobId, message, java.util.Collections.emptyList());
    }

    /**
     * 同上，只发到步骤里指定的通道；{@code channelIds} 为空时按通道规则匹配。
     *
     * @return 给步骤日志用的结果说明
     */
    String sendStepMessage(Long buildJobId, String message, java.util.List<Long> channelIds);

    /**
     * 替换通知步骤消息里的变量：${service} ${env} ${branch} ${image} ${jobNumber} ${trigger} ${project} ${pipeline}。
     * 一次性文本替换，替换进去的值不再解析；未知变量原样保留，空值换成空串。
     *
     * @param runtime 运行时才有的值（目前只有 image：镜像还没回写到 Job 时取当前步骤参数），可为 null
     */
    default String renderStepMessage(Long buildJobId, String template, java.util.Map<String, String> runtime) {
        return template;
    }
}
