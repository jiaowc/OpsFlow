package com.opsflow.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.opsflow.dao.model.DeployTask;
import org.apache.ibatis.annotations.Mapper;

/**
 * 上线任务Mapper
 */
@Mapper
public interface DeployTaskMapper extends BaseMapper<DeployTask> {
}


