package com.opsflow.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.opsflow.dao.model.PipelineStepDef;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PipelineStepDefMapper extends BaseMapper<PipelineStepDef> {
}
