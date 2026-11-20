package com.opsflow.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.opsflow.dao.model.BuildJob;
import com.opsflow.dao.model.BuildJobGroupPageRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

/**
 * 构建任务 Mapper。
 */
@Mapper
public interface BuildJobMapper extends BaseMapper<BuildJob> {

    /**
     * 统计流水线列表「任务组」数量（与 {@link #selectPipelineJobGroupPage} 分组规则一致）。
     *
     * @param envId  指定环境；与 envIds 互斥优先用 envId
     * @param envIds 多环境过滤；envId 为空时生效；二者皆空表示不过滤环境
     * @param serviceIds 服务过滤（项目隔离）；null 表示不过滤
     * @param keyword 模糊搜索：服务/仓库/分支/环境/用户等；空表示不过滤
     */
    long countPipelineJobGroups(@Param("envId") Long envId,
                                @Param("envIds") Collection<Long> envIds,
                                @Param("serviceIds") Collection<Long> serviceIds,
                                @Param("keyword") String keyword);

    /**
     * 按任务组分页，返回每组 latest/anchor Job ID。
     */
    List<BuildJobGroupPageRow> selectPipelineJobGroupPage(@Param("envId") Long envId,
                                                         @Param("envIds") Collection<Long> envIds,
                                                         @Param("serviceIds") Collection<Long> serviceIds,
                                                         @Param("keyword") String keyword,
                                                         @Param("offset") int offset,
                                                         @Param("limit") int limit);
}
