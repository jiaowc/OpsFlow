package com.opsflow.dao.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 流水线任务的 Git Webhook 自动构建开关，按任务组首条 build_job（anchor）关联。
 */
@Data
@TableName("pipeline_webhook_trigger")
public class PipelineWebhookTrigger {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long anchorJobId;

    /**
     * 1 推送代码时自动构建 / 0 关闭
     */
    private Integer enabled;

    private String updateBy;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
