package com.opsflow.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.opsflow.dao.model.InboxMessage;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface InboxMessageMapper extends BaseMapper<InboxMessage> {
}
