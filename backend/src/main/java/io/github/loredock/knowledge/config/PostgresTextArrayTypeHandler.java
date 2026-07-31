package io.github.loredock.knowledge.config;

import java.sql.Array;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

/** 使用 JDBC Array 参数读写 PostgreSQL text[]，避免手工拼接数组字面量及其转义风险。 */
@MappedTypes(String[].class)
@MappedJdbcTypes(JdbcType.ARRAY)
public final class PostgresTextArrayTypeHandler extends BaseTypeHandler<String[]> {

    @Override
    public void setNonNullParameter(
            PreparedStatement statement,
            int index,
            String[] parameter,
            JdbcType jdbcType
    ) throws SQLException {
        statement.setArray(index, statement.getConnection().createArrayOf("text", parameter));
    }

    @Override
    public String[] getNullableResult(ResultSet resultSet, String columnName) throws SQLException {
        return strings(resultSet.getArray(columnName));
    }

    @Override
    public String[] getNullableResult(ResultSet resultSet, int columnIndex) throws SQLException {
        return strings(resultSet.getArray(columnIndex));
    }

    @Override
    public String[] getNullableResult(CallableStatement statement, int columnIndex) throws SQLException {
        return strings(statement.getArray(columnIndex));
    }

    private String[] strings(Array array) throws SQLException {
        return array == null ? null : (String[]) array.getArray();
    }
}
