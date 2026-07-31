package io.github.loredock.platform.persistence;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

/** 将经过上层校验和长度限制的 JSON 字符串显式读写为 PostgreSQL JSONB。 */
public class PostgresJsonbTypeHandler extends BaseTypeHandler<String> {

    @Override
    public void setNonNullParameter(PreparedStatement statement, int index, String parameter, JdbcType jdbcType)
            throws SQLException {
        // 使用 JDBC OTHER 让 PostgreSQL 驱动按目标列 JSONB 解析，避免平台公共层反向依赖具体驱动 API。
        statement.setObject(index, parameter, Types.OTHER);
    }

    @Override
    public String getNullableResult(ResultSet resultSet, String columnName) throws SQLException {
        return json(resultSet.getObject(columnName));
    }

    @Override
    public String getNullableResult(ResultSet resultSet, int columnIndex) throws SQLException {
        return json(resultSet.getObject(columnIndex));
    }

    @Override
    public String getNullableResult(CallableStatement statement, int columnIndex) throws SQLException {
        return json(statement.getObject(columnIndex));
    }

    private String json(Object value) {
        return value == null ? null : value.toString();
    }
}
