package com.opsflow.dao.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 构建任务阶段（平台原生流水线运行时数据）
 */
@Data
@TableName("build_job_stage")
public class BuildJobStage {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long buildJobId;

    private Integer stepOrder;

    private String stepType;

    private String stepName;

    /**
     * PENDING / RUNNING / SUCCESS / FAILURE / SKIPPED
     */
    private String status;

    private Long durationMs;

    private String logText;

    private String errorMessage;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
