package com.opsflow.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.opsflow.dao.model.PipelineViewRole;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface PipelineViewRoleMapper extends BaseMapper<PipelineViewRole> {

    void deleteByViewId(@Param("viewId") Long viewId);

    List<Long> selectRoleIdsByViewId(@Param("viewId") Long viewId);

    List<Long> selectViewIdsByRoleIds(@Param("roleIds") List<Long> roleIds);
}
