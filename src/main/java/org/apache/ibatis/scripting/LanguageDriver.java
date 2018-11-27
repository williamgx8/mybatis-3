/**
 * Copyright 2009-2016 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ibatis.scripting;

import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.session.Configuration;

public interface LanguageDriver {

	/**
	 * Creates a {@link ParameterHandler} that passes the actual parameters to the the JDBC statement.
	 * 创建参数处理器，参数处理器用于处理方法参数，使其映射成为SQL语句的一部分
	 *
	 * @param mappedStatement The mapped statement that is being executed
	 * @param parameterObject The input parameter object (can be null)
	 * @param boundSql The resulting SQL once the dynamic language has been executed.
	 * @author Frank D. Martinez [mnesarco]
	 * @see DefaultParameterHandler
	 */
	ParameterHandler createParameterHandler(MappedStatement mappedStatement, Object parameterObject,
		BoundSql boundSql);

	/**
	 * Creates an {@link SqlSource} that will hold the statement read from a mapper xml file.
	 * It is called during startup, when the mapped statement is read from a class or an xml file.
	 * SqlSource对象持有所有的语句标签/注解语句 解析后的结果
	 *
	 * @param configuration The MyBatis configuration
	 * @param script XNode parsed from a XML file
	 * @param parameterType input parameter type got from a mapper method or specified in the parameterType xml attribute. Can be null.
	 */
	SqlSource createSqlSource(Configuration configuration, XNode script, Class<?> parameterType);

	/**
	 * Creates an {@link SqlSource} that will hold the statement read from an annotation.
	 * It is called during startup, when the mapped statement is read from a class or an xml file.
	 * SqlSource对象持有所有的语句标签/注解语句 解析后的结果
	 *
	 * @param configuration The MyBatis configuration
	 * @param script The content of the annotation
	 * @param parameterType input parameter type got from a mapper method or specified in the parameterType xml attribute. Can be null.
	 */
	SqlSource createSqlSource(Configuration configuration, String script, Class<?> parameterType);

}
