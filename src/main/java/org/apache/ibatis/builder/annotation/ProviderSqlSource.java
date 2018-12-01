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
package org.apache.ibatis.builder.annotation;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.builder.SqlSourceBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.parsing.PropertyParser;
import org.apache.ibatis.reflection.ParamNameResolver;
import org.apache.ibatis.session.Configuration;

/**
 * @author Clinton Begin
 * @author Kazuki Shimizu
 */
public class ProviderSqlSource implements SqlSource {

	private final Configuration configuration;
	private final SqlSourceBuilder sqlSourceParser;
	private final Class<?> providerType;
	private Method providerMethod;
	private String[] providerMethodArgumentNames;
	private Class<?>[] providerMethodParameterTypes;
	private ProviderContext providerContext;
	private Integer providerContextIndex;

	/**
	 * @deprecated Please use the {@link #ProviderSqlSource(Configuration, Object, Class, Method)} instead of this.
	 */
	@Deprecated
	public ProviderSqlSource(Configuration configuration, Object provider) {
		this(configuration, provider, null, null);
	}

	/**
	 * 解析@Provider注解生成ProviderSqlSource，举例：
	 *
	 * @param provider 任何一种@Provider注解实例
	 * @SelectProvider(type = UserSqlBuilder.class, method = "buildGetUsersByName")
	 * List<User> getUsersByName(String name);
	 * <p>
	 * class UserSqlBuilder {
	 * public static String buildGetUsersByName(final String name) {
	 * return new SQL(){{
	 * SELECT("*");
	 * FROM("users");
	 * if (name != null) {
	 * WHERE("name like #{value} || '%'");
	 * }
	 * ORDER_BY("id");
	 * }}.toString();
	 * }
	 * }
	 * @since 3.4.5
	 */
	public ProviderSqlSource(Configuration configuration, Object provider, Class<?> mapperType,
		Method mapperMethod) {
		String providerMethodName;
		try {
			this.configuration = configuration;
			this.sqlSourceParser = new SqlSourceBuilder(configuration);
			//type属性，对应构建sql语句的类
			this.providerType = (Class<?>) provider.getClass().getMethod("type").invoke(provider);
			//类中构建sql语句的方法
			providerMethodName = (String) provider.getClass().getMethod("method").invoke(provider);

			//遍历构建sql语句类的所有方法
			for (Method m : this.providerType.getMethods()) {
				//找到生成sql语句的方法
				if (providerMethodName.equals(m.getName()) && CharSequence.class
					.isAssignableFrom(m.getReturnType())) {
					//如果此时生成的方法已经有了，有问题，抛出异常
					if (providerMethod != null) {
						throw new BuilderException(
							"Error creating SqlSource for SqlProvider. Method '"
								+ providerMethodName + "' is found multiple in SqlProvider '"
								+ this.providerType
								.getName()
								+ "'. Sql provider method can not overload.");
					}
					//保存生成sql的方法
					this.providerMethod = m;
					//解析参数名称，处理参数上@Param注解
					this.providerMethodArgumentNames = new ParamNameResolver(configuration, m)
						.getNames();
					//对应参数类型
					this.providerMethodParameterTypes = m.getParameterTypes();
				}
			}
		} catch (BuilderException e) {
			throw e;
		} catch (Exception e) {
			throw new BuilderException("Error creating SqlSource for SqlProvider.  Cause: " + e, e);
		}
		if (this.providerMethod == null) {
			throw new BuilderException("Error creating SqlSource for SqlProvider. Method '"
				+ providerMethodName + "' not found in SqlProvider '" + this.providerType.getName()
				+ "'.");
		}
		for (int i = 0; i < this.providerMethodParameterTypes.length; i++) {
			Class<?> parameterType = this.providerMethodParameterTypes[i];
			//Mybatis3.4.5以上版本支持的ProviderContext，该参数可以将当前调用sqlbuilder的Mapper类型和参数封装起来
			//平常在写sql builder中的语句时，比如表名这种字段都是写死的，当有一些公共builder时，无法设置不同的表明，此时
			//该参数就可以动态拼装表明
			if (parameterType == ProviderContext.class) {
				//该参数只能有一个
				if (this.providerContext != null) {
					throw new BuilderException(
						"Error creating SqlSource for SqlProvider. ProviderContext found multiple in SqlProvider method ("
							+ this.providerType.getName() + "." + providerMethod.getName()
							+ "). ProviderContext can not define multiple in SqlProvider method argument.");
				}
				//封装Mapper类型及调用方法的参数
				this.providerContext = new ProviderContext(mapperType, mapperMethod);
				//记录ProviderContext是在参数中的第几个
				this.providerContextIndex = i;
			}
		}
	}

	@Override
	public BoundSql getBoundSql(Object parameterObject) {
		SqlSource sqlSource = createSqlSource(parameterObject);
		return sqlSource.getBoundSql(parameterObject);
	}

	private SqlSource createSqlSource(Object parameterObject) {
		try {
			int bindParameterCount =
				providerMethodParameterTypes.length - (providerContext == null ? 0 : 1);
			String sql;
			if (providerMethodParameterTypes.length == 0) {
				sql = invokeProviderMethod();
			} else if (bindParameterCount == 0) {
				sql = invokeProviderMethod(providerContext);
			} else if (bindParameterCount == 1 &&
				(parameterObject == null || providerMethodParameterTypes[
					(providerContextIndex == null || providerContextIndex == 1) ? 0 : 1]
					.isAssignableFrom(parameterObject.getClass()))) {
				sql = invokeProviderMethod(extractProviderMethodArguments(parameterObject));
			} else if (parameterObject instanceof Map) {
				@SuppressWarnings("unchecked")
				Map<String, Object> params = (Map<String, Object>) parameterObject;
				sql = invokeProviderMethod(
					extractProviderMethodArguments(params, providerMethodArgumentNames));
			} else {
				throw new BuilderException("Error invoking SqlProvider method ("
					+ providerType.getName() + "." + providerMethod.getName()
					+ "). Cannot invoke a method that holds "
					+ (bindParameterCount == 1 ? "named argument(@Param)" : "multiple arguments")
					+ " using a specifying parameterObject. In this case, please specify a 'java.util.Map' object.");
			}
			Class<?> parameterType =
				parameterObject == null ? Object.class : parameterObject.getClass();
			return sqlSourceParser
				.parse(replacePlaceholder(sql), parameterType, new HashMap<String, Object>());
		} catch (BuilderException e) {
			throw e;
		} catch (Exception e) {
			throw new BuilderException("Error invoking SqlProvider method ("
				+ providerType.getName() + "." + providerMethod.getName()
				+ ").  Cause: " + e, e);
		}
	}

	private Object[] extractProviderMethodArguments(Object parameterObject) {
		if (providerContext != null) {
			Object[] args = new Object[2];
			args[providerContextIndex == 0 ? 1 : 0] = parameterObject;
			args[providerContextIndex] = providerContext;
			return args;
		} else {
			return new Object[]{parameterObject};
		}
	}

	private Object[] extractProviderMethodArguments(Map<String, Object> params,
		String[] argumentNames) {
		Object[] args = new Object[argumentNames.length];
		for (int i = 0; i < args.length; i++) {
			if (providerContextIndex != null && providerContextIndex == i) {
				args[i] = providerContext;
			} else {
				args[i] = params.get(argumentNames[i]);
			}
		}
		return args;
	}

	private String invokeProviderMethod(Object... args) throws Exception {
		Object targetObject = null;
		if (!Modifier.isStatic(providerMethod.getModifiers())) {
			targetObject = providerType.newInstance();
		}
		CharSequence sql = (CharSequence) providerMethod.invoke(targetObject, args);
		return sql != null ? sql.toString() : null;
	}

	private String replacePlaceholder(String sql) {
		return PropertyParser.parse(sql, configuration.getVariables());
	}

}
