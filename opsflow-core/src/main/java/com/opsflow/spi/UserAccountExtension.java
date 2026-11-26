package com.opsflow.spi;

import com.opsflow.api.dto.UserDTO;
import com.opsflow.dao.model.User;

/**
 * 用户管理的扩展：保存用户后处理扩展字段（如第三方账号绑定），返回用户详情前补充扩展字段。可以有多个实现。
 */
public interface UserAccountExtension {

    /** 新建或修改用户、用户和角色都已写库之后调用；异常会让本次保存失败 */
    default void afterSave(User user, UserDTO request) {
    }

    /** 组装用户详情时调用，可往 {@code dto} 里补字段 */
    default void decorate(User user, UserDTO dto) {
    }
}
