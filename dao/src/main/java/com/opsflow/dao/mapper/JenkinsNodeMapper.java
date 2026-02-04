package com.opsflow.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.opsflow.dao.model.JenkinsNode;
import org.apache.ibatis.annotations.Mapper;

/**
 * Jenkins节点Mapper
 */
@Mapper
public interface JenkinsNodeMapper extends BaseMapper<JenkinsNode> {
}



