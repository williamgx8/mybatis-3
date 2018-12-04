/**
 * Copyright 2009-2015 the original author or authors.
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
package org.apache.ibatis.scripting.defaults;

import java.util.HashMap;

import org.apache.ibatis.builder.SqlSourceBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.scripting.xmltags.DynamicContext;
import org.apache.ibatis.scripting.xmltags.DynamicSqlSource;
import org.apache.ibatis.scripting.xmltags.SqlNode;
import org.apache.ibatis.session.Configuration;

/**
 * Static SqlSource. It is faster than {@link DynamicSqlSource} because mappings are
 * calculated during startup.
 * 非动态的，普通sql文本SqlSource
 *
 * @since 3.2.0
 * author Eduardo Macarron
 */
public class RawSqlSource implements SqlSource {

	private final SqlSource sqlSource;

	public RawSqlSource(Configuration configuration, SqlNode rootSqlNode, Class<?> parameterType) {
		this(configuration, getSql(configuration, rootSqlNode), parameterType);
	}

	public RawSqlSource(Configuration configuration, String sql, Class<?> parameterType) {
		SqlSourceBuilder sqlSourceParser = new SqlSourceBuilder(configuration);
		Class<?> clazz = parameterType == null ? Object.class : parameterType;
		//将经过一次解析后的sql中可能存在的#{}替换为？，返回StaticSqlSource
		sqlSource = sqlSourceParser.parse(sql, clazz, new HashMap<String, Object>());
	}

	/**
	 * 获取解析完毕的sql，其中可能包含？，RawSqlSource的rootSqlNode中不存在任何${}和诸如<if/>动态标签的内容
	 *
	 * @param rootSqlNode 顶层SqlNode
	 */
	private static String getSql(Configuration configuration, SqlNode rootSqlNode) {
		DynamicContext context = new DynamicContext(configuration, null);
		//单纯的文本应用，此时rootSqlNode只可能是StaticSqlNode和MixedSqlNode两种之一
		rootSqlNode.apply(context);
		//返回处理完的sql
		return context.getSql();
	}

	@Override
	public BoundSql getBoundSql(Object parameterObject) {
		return sqlSource.getBoundSql(parameterObject);
	}

}
