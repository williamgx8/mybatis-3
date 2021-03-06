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
import java.sql.SQLException;

/**
 * 类型处理器的抽象，泛型为JavaType
 *
 * @author Clinton Begin
 */
public interface TypeHandler<T> {

    /**
     * 设置PreparedStatement的指定参数
     * Java Type --->  JDBC Type
     *
     * @param ps        PreparedStatement对象
     * @param i         参数占位符的位置
     * @param parameter 参数
     * @param jdbcType  JDBC类型
     */
    void setParameter(PreparedStatement ps, int i, T parameter, JdbcType jdbcType)
            throws SQLException;

    /**
     * 获取ResultSet中指定字段的值
     * JDBC Type --->  Java Type
     *
     * @param rs         结果集
     * @param columnName 要获取的字段名
     */
    T getResult(ResultSet rs, String columnName) throws SQLException;

    /**
     * 获取ResultSet中指定index字段的值
     * JDBC Type --->  Java Type
     *
     * @param rs          结果集
     * @param columnIndex 列下标
     */
    T getResult(ResultSet rs, int columnIndex) throws SQLException;

    /**
     * 获取存储过程结果集中指定index字段的值
     * JDBC Type --->  Java Type
     */
    T getResult(CallableStatement cs, int columnIndex) throws SQLException;

}
