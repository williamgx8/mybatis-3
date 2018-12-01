/**
 *    Copyright 2009-2018 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.type;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

import org.apache.ibatis.io.Resources;

/**
 * @author Clinton Begin
 */
public class UnknownTypeHandler extends BaseTypeHandler<Object> {

    private static final ObjectTypeHandler OBJECT_TYPE_HANDLER = new ObjectTypeHandler();

    private TypeHandlerRegistry typeHandlerRegistry;

    public UnknownTypeHandler(TypeHandlerRegistry typeHandlerRegistry) {
        this.typeHandlerRegistry = typeHandlerRegistry;
    }

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, Object parameter, JdbcType jdbcType)
            throws SQLException {
        TypeHandler handler = resolveTypeHandler(parameter, jdbcType);
        handler.setParameter(ps, i, parameter, jdbcType);
    }

    @Override
    public Object getNullableResult(ResultSet rs, String columnName)
            throws SQLException {
        TypeHandler<?> handler = resolveTypeHandler(rs, columnName);
        return handler.getResult(rs, columnName);
    }

    @Override
    public Object getNullableResult(ResultSet rs, int columnIndex)
            throws SQLException {
        TypeHandler<?> handler = resolveTypeHandler(rs.getMetaData(), columnIndex);
        if (handler == null || handler instanceof UnknownTypeHandler) {
            handler = OBJECT_TYPE_HANDLER;
        }
        return handler.getResult(rs, columnIndex);
    }

    @Override
    public Object getNullableResult(CallableStatement cs, int columnIndex)
            throws SQLException {
        return cs.getObject(columnIndex);
    }

    /**
     * 根据参数和jdbc类型选择合适的类型解析器
     *
     * @param parameter
     * @param jdbcType
     * @return
     */
    private TypeHandler<? extends Object> resolveTypeHandler(Object parameter, JdbcType jdbcType) {
        TypeHandler<? extends Object> handler;
        //没有属性-->ObjectTypeHandler
        if (parameter == null) {
            handler = OBJECT_TYPE_HANDLER;
        } else {
            //实际上有委托给TypeHandler注册器去选择
            handler = typeHandlerRegistry.getTypeHandler(parameter.getClass(), jdbcType);
            // check if handler is null (issue #270)
            //没找到合适的依然返回ObjectTypeHandler
            if (handler == null || handler instanceof UnknownTypeHandler) {
                handler = OBJECT_TYPE_HANDLER;
            }
        }
        return handler;
    }

    /**
     * 根据结果集ResultSet和列名选择类型处理器
     *
     * @param rs     结果集
     * @param column 列名
     * @return
     */
    private TypeHandler<?> resolveTypeHandler(ResultSet rs, String column) {
        try {
            //列名和索引的映射
            Map<String, Integer> columnIndexLookup;
            columnIndexLookup = new HashMap<>();
            //获得结果集元数据
            ResultSetMetaData rsmd = rs.getMetaData();
            int count = rsmd.getColumnCount();
            //遍历每一个列，放入map
            for (int i = 1; i <= count; i++) {
                String name = rsmd.getColumnName(i);
                columnIndexLookup.put(name, i);
            }
            //得到column对应的index
            Integer columnIndex = columnIndexLookup.get(column);
            TypeHandler<?> handler = null;
            if (columnIndex != null) {
                //获得类型处理器
                handler = resolveTypeHandler(rsmd, columnIndex);
            }
            //找不到返回ObjectTypeHandler
            if (handler == null || handler instanceof UnknownTypeHandler) {
                handler = OBJECT_TYPE_HANDLER;
            }
            return handler;
        } catch (SQLException e) {
            throw new TypeException("Error determining JDBC type for column " + column + ".  Cause: " + e, e);
        }
    }

    /**
     * 根据结果集元数据和列下标获得类型处理器
     *
     * @param rsmd        结果集元数据
     * @param columnIndex 列下标
     * @return
     */
    private TypeHandler<?> resolveTypeHandler(ResultSetMetaData rsmd, Integer columnIndex) {
        TypeHandler<?> handler = null;
        //获得下标对应的jdbc类型
        JdbcType jdbcType = safeGetJdbcTypeForColumn(rsmd, columnIndex);
        //获得下标对应的java类型
        Class<?> javaType = safeGetClassForColumn(rsmd, columnIndex);
        //同样交给类型处理注册器处理
        if (javaType != null && jdbcType != null) {
            handler = typeHandlerRegistry.getTypeHandler(javaType, jdbcType);
        } else if (javaType != null) {
            handler = typeHandlerRegistry.getTypeHandler(javaType);
        } else if (jdbcType != null) {
            handler = typeHandlerRegistry.getTypeHandler(jdbcType);
        }
        return handler;
    }

    private JdbcType safeGetJdbcTypeForColumn(ResultSetMetaData rsmd, Integer columnIndex) {
        try {
            //获取列下标对应的列类型，映射到JdbcType枚举的某一项中
            return JdbcType.forCode(rsmd.getColumnType(columnIndex));
        } catch (Exception e) {
            return null;
        }
    }

    private Class<?> safeGetClassForColumn(ResultSetMetaData rsmd, Integer columnIndex) {
        try {
            //获取列下标对应的列的 JavaType
            return Resources.classForName(rsmd.getColumnClassName(columnIndex));
        } catch (Exception e) {
            return null;
        }
    }
}
