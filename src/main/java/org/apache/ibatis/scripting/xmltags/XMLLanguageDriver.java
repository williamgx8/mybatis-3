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
package org.apache.ibatis.scripting.xmltags;

import org.apache.ibatis.builder.xml.XMLMapperEntityResolver;
import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.parsing.PropertyParser;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.parsing.XPathParser;
import org.apache.ibatis.scripting.LanguageDriver;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.scripting.defaults.RawSqlSource;
import org.apache.ibatis.session.Configuration;

/**
 * 用于解析xml的语言驱动
 *
 * @author Eduardo Macarron
 */
public class XMLLanguageDriver implements LanguageDriver {

	@Override
	public ParameterHandler createParameterHandler(MappedStatement mappedStatement,
		Object parameterObject, BoundSql boundSql) {
		//默认参数参数处理器
		return new DefaultParameterHandler(mappedStatement, parameterObject, boundSql);
	}

	@Override
	public SqlSource createSqlSource(Configuration configuration, XNode script,
		Class<?> parameterType) {
		//对语句标签进行解析，封装SqlSource返回
		XMLScriptBuilder builder = new XMLScriptBuilder(configuration, script, parameterType);
		return builder.parseScriptNode();
	}

	@Override
	public SqlSource createSqlSource(Configuration configuration, String script,
		Class<?> parameterType) {
		// issue #3
		//如果是脚本，比如 <script>select * from user <if test=\"id !=null \">where id = #{id} </if></script>
		if (script.startsWith("<script>")) {
			//构建xpath解析器，从<script>里面开始解析
			XPathParser parser = new XPathParser(script, false, configuration.getVariables(),
				new XMLMapperEntityResolver());
			return createSqlSource(configuration, parser.evalNode("/script"), parameterType);
		} else {
			// issue #127
			//处理${}占位符
			script = PropertyParser.parse(script, configuration.getVariables());
			TextSqlNode textSqlNode = new TextSqlNode(script);
			if (textSqlNode.isDynamic()) {
				//动态sql创建DynamicSqlSource
				return new DynamicSqlSource(configuration, textSqlNode);
			} else {
				//普通sql
				return new RawSqlSource(configuration, script, parameterType);
			}
		}
	}

}
