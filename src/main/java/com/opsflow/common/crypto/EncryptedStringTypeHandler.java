package com.opsflow.common.crypto;

import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 凭据字段透明加解密：写库加密，读库解密（兼容历史明文）。
 *
 * <p>用法：实体 {@code @TableName(autoResultMap = true)}，字段
 * {@code @TableField(typeHandler = EncryptedStringTypeHandler.class)}。
 * 注意：经 Wrapper 的 {@code set("列", 值)} 写入不会走本 Handler，敏感列不要这样更新。</p>
 *
 * <p>解密失败（主密钥被换掉）时返回 null 并记错误日志，而不是抛异常让整个列表页不可用；
 * MyBatis-Plus 默认更新策略会跳过 null 字段，不会把库里的密文覆盖掉。</p>
 */
@Slf4j
// 刻意不加 @MappedTypes：只在显式标注的字段上生效，绝不能变成全局 String 处理器
public class EncryptedStringTypeHandler extends BaseTypeHandler<String> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType) throws SQLException {
        ps.setString(i, SecretCipher.encrypt(parameter));
    }

    @Override
    public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return decrypt(rs.getString(columnName), columnName);
    }

    @Override
    public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return decrypt(rs.getString(columnIndex), String.valueOf(columnIndex));
    }

    @Override
    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return decrypt(cs.getString(columnIndex), String.valueOf(columnIndex));
    }

    private String decrypt(String value, String column) {
        try {
            return SecretCipher.decrypt(value);
        } catch (SecretCipher.SecretDecryptException e) {
            log.error("字段 {} 解密失败，按未配置处理：{}", column, e.getMessage());
            return null;
        }
    }
}
