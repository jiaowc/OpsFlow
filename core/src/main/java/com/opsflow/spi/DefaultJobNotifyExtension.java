package com.opsflow.spi;

import com.opsflow.integration.notify.NotifyMessage;

/** 社区版：只发站内信（由核心负责），没有外部通道 */
public class DefaultJobNotifyExtension implements JobNotifyExtension {

    static final String NO_CHANNEL_MESSAGE = "社区版不含外部通知通道，跳过";

    @Override
    public void onJobFinished(JobNotifyContext context, NotifyMessage message) {
    }

    @Override
    public String sendStepMessage(JobNotifyContext context, NotifyMessage message) {
        return NO_CHANNEL_MESSAGE;
    }
}
