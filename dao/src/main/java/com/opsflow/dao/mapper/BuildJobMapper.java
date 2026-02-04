package com.opsflow.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.opsflow.dao.model.BuildJob;
import org.apache.ibatis.annotations.Mapper;

/**
 * 构建任务Mapper
 */
@Mapper
public interface BuildJobMapper extends BaseMapper<BuildJob> {
}



