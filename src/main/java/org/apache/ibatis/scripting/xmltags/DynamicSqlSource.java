/**
 * Copyright 2009-2017 the original author or authors.
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
package org.apache.ibatis.scripting.xmltags;

import java.util.Map;

import org.apache.ibatis.builder.SqlSourceBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.session.Configuration;

/**
 * @author Clinton Begin
 */
public class DynamicSqlSource implements SqlSource {

	private final Configuration configuration;
	//根节点
	private final SqlNode rootSqlNode;

	public DynamicSqlSource(Configuration configuration, SqlNode rootSqlNode) {
		this.configuration = configuration;
		this.rootSqlNode = rootSqlNode;
	}

	@Override
	public BoundSql getBoundSql(Object parameterObject) {
		//将参数和配置封装成动态上下文
		DynamicContext context = new DynamicContext(configuration, parameterObject);
		//从根节点依次往下处理sql内容，处理每个<choose/>等节点内的sql，拼装起来放在动态上下文中
		//其中封装${}的TextSqlNode会将${}处理掉，剩下的就是#{}类型的占位符
		rootSqlNode.apply(context);
		SqlSourceBuilder sqlSourceParser = new SqlSourceBuilder(configuration);
		Class<?> parameterType =
			parameterObject == null ? Object.class : parameterObject.getClass();
		//生成StaticSqlSource，如果sql中存在#{}替换成?
		SqlSource sqlSource = sqlSourceParser
			.parse(context.getSql(), parameterType, context.getBindings());
		//单纯的封装BoundSql，此时还有可能存在？
		BoundSql boundSql = sqlSource.getBoundSql(parameterObject);
		//将？对应的真实值放入，并没有处理
		for (Map.Entry<String, Object> entry : context.getBindings().entrySet()) {
			boundSql.setAdditionalParameter(entry.getKey(), entry.getValue());
		}
		return boundSql;
	}

}
