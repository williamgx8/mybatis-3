/**
 * Copyright 2009-2018 the original author or authors.
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
package org.apache.ibatis.builder;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.mapping.ResultSetType;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeAliasRegistry;
import org.apache.ibatis.type.TypeHandler;
import org.apache.ibatis.type.TypeHandlerRegistry;

/**
 * 各种构造器的基类，比如XmlMapperBuilder、XmlConfigBuilder、XmlScriptBuilder、XmlStatementBuilder
 * @author Clinton Begin
 */
public abstract class BaseBuilder {

	protected final Configuration configuration;
	//处理别名和真实类型的映射
	protected final TypeAliasRegistry typeAliasRegistry;
	//处理JavaType、JdbcType、TypeHandler三者的映射
	protected final TypeHandlerRegistry typeHandlerRegistry;

	public BaseBuilder(Configuration configuration) {
		this.configuration = configuration;
		this.typeAliasRegistry = this.configuration.getTypeAliasRegistry();
		this.typeHandlerRegistry = this.configuration.getTypeHandlerRegistry();
	}

	public Configuration getConfiguration() {
		return configuration;
	}

	/**
	 * 解析正则表达式
	 *
	 * @param regex 正则表达式
	 * @param defaultValue 默认正则表达式
	 */
	protected Pattern parseExpression(String regex, String defaultValue) {
		return Pattern.compile(regex == null ? defaultValue : regex);
	}

	protected Boolean booleanValueOf(String value, Boolean defaultValue) {
		return value == null ? defaultValue : Boolean.valueOf(value);
	}

	protected Integer integerValueOf(String value, Integer defaultValue) {
		return value == null ? defaultValue : Integer.valueOf(value);
	}

	protected Set<String> stringSetValueOf(String value, String defaultValue) {
		value = (value == null ? defaultValue : value);
		return new HashSet<>(Arrays.asList(value.split(",")));
	}

	/**
	 * 解析别名对应JdbcType，alias实际上就是枚举值的字符串
	 *
	 * @param alias 枚举值的字符串
	 * @return JdbcType
	 */
	protected JdbcType resolveJdbcType(String alias) {
		if (alias == null) {
			return null;
		}
		try {
			return JdbcType.valueOf(alias);
		} catch (IllegalArgumentException e) {
			throw new BuilderException("Error resolving JdbcType. Cause: " + e, e);
		}
	}

	/**
	 * 根据结果集名称得到结果集枚举类型
	 * @param alias 结果集名称
	 * @return
	 */
	protected ResultSetType resolveResultSetType(String alias) {
		if (alias == null) {
			return null;
		}
		try {
			return ResultSetType.valueOf(alias);
		} catch (IllegalArgumentException e) {
			throw new BuilderException("Error resolving ResultSetType. Cause: " + e, e);
		}
	}

	protected ParameterMode resolveParameterMode(String alias) {
		if (alias == null) {
			return null;
		}
		try {
			return ParameterMode.valueOf(alias);
		} catch (IllegalArgumentException e) {
			throw new BuilderException("Error resolving ParameterMode. Cause: " + e, e);
		}
	}

	/**
	 * 根据类的别名创建类的实例
	 * @param alias 类的别名
	 * @return
	 */
	protected Object createInstance(String alias) {
		//解析别名
		Class<?> clazz = resolveClass(alias);
		//解析不到返回null
		if (clazz == null) {
			return null;
		}
		try {
			//解析成功创建实例
			return resolveClass(alias).newInstance();
		} catch (Exception e) {
			throw new BuilderException("Error creating instance. Cause: " + e, e);
		}
	}

	protected <T> Class<? extends T> resolveClass(String alias) {
		if (alias == null) {
			return null;
		}
		try {
			return resolveAlias(alias);
		} catch (Exception e) {
			throw new BuilderException("Error resolving class. Cause: " + e, e);
		}
	}

	/**
	 * 根据java类型和类型处理器TypeHandler昵称得到对应的TypeHandler
	 * @param javaType Java类型
	 * @param typeHandlerAlias TypeHandler别名
	 * @return
	 */
	protected TypeHandler<?> resolveTypeHandler(Class<?> javaType, String typeHandlerAlias) {
		if (typeHandlerAlias == null) {
			return null;
		}
		//解析别名得到对应的TypeHandler类型
		Class<?> type = resolveClass(typeHandlerAlias);
		//验证解析出来的是TypeHandler的子类
		if (type != null && !TypeHandler.class.isAssignableFrom(type)) {
			throw new BuilderException("Type " + type.getName()
				+ " is not a valid TypeHandler because it does not implement TypeHandler interface");
		}
		@SuppressWarnings("unchecked") // already verified it is a TypeHandler
			Class<? extends TypeHandler<?>> typeHandlerType = (Class<? extends TypeHandler<?>>) type;
		return resolveTypeHandler(javaType, typeHandlerType);
	}

	/**
	 * 根据TypeHandler类型和JavaType得到对应TypeHandler实例
	 * @param javaType java类型
	 * @param typeHandlerType TypeHandler类型
	 * @return
	 */
	protected TypeHandler<?> resolveTypeHandler(Class<?> javaType,
		Class<? extends TypeHandler<?>> typeHandlerType) {
		if (typeHandlerType == null) {
			return null;
		}
		// javaType ignored for injected handlers see issue #746 for full detail
		//获得对应实例
		TypeHandler<?> handler = typeHandlerRegistry.getMappingTypeHandler(typeHandlerType);
		//之前没在
		if (handler == null) {
			// not in registry, create a new one
			handler = typeHandlerRegistry.getInstance(javaType, typeHandlerType);
		}
		return handler;
	}

	protected <T> Class<? extends T> resolveAlias(String alias) {
		return typeAliasRegistry.resolveAlias(alias);
	}
}
