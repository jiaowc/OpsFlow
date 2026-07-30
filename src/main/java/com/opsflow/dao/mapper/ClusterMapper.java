package com.opsflow.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.opsflow.dao.model.Cluster;
import org.apache.ibatis.annotations.Mapper;

/**
 * 集群 Mapper
 */
@Mapper
public interface ClusterMapper extends BaseMapper<Cluster> {
}
