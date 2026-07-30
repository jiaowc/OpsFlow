package com.opsflow.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.opsflow.dao.model.Credential;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CredentialMapper extends BaseMapper<Credential> {
}
