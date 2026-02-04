package com.opsflow.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.opsflow.dao.model.Pipeline;
import org.apache.ibatis.annotations.Mapper;

/**
 * Pipeline Mapper
 */
@Mapper
public interface PipelineMapper extends BaseMapper<Pipeline> {
}


