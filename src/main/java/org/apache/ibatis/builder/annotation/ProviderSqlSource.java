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
 * @ xxxProvider注解对应SqlSource实例
 */
public class ProviderSqlSource implements SqlSource {

	private final Configuration configuration;
	//SqlSource构建器
	private final SqlSourceBuilder sqlSourceParser;
	//构建sql的provider构建类
	private final Class<?> providerType;
	//provider构建类中构建sql的具体方法
	private Method providerMethod;
	//构建方法的参数列表
	private String[] providerMethodArgumentNames;
	//构建方法参数列表对应的参数类型列表
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
		//该类本身就是SqlSource，为什么还要创建一个SqlSource呢？
		//因为，对于xml和annotation直接写sql的形式，这两种实在初始化时就已经将sql进行了部分解析
		//但对于@xxxProvider这种形式，初始化时只会封装成ProviderSqlSource，而sql仍然封装在builder类中
		//所以先要解析builder类中特定产生sql的方法，将sql解析出来封装成sql真正对应的SqlSource，才能
		//和上面两种情况一样获得BoundSql
		SqlSource sqlSource = createSqlSource(parameterObject);
		return sqlSource.getBoundSql(parameterObject);
	}

	/**
	 * 从@xxxProvider注解指向的真正产生sql构建类的方法中提取出sql，并封装成sql对应的SqlSource
	 *
	 * @param parameterObject 参数
	 */
	private SqlSource createSqlSource(Object parameterObject) {
		try {
			//除去ProviderContext作为参数的其他参数数量
			int bindParameterCount =
				providerMethodParameterTypes.length - (providerContext == null ? 0 : 1);
			String sql;
			if (providerMethodParameterTypes.length == 0) {
				//没有参数直接拼装sql
				sql = invokeProviderMethod();
			} else if (bindParameterCount == 0) {
				//参数为一个ProviderContext对象
				sql = invokeProviderMethod(providerContext);
			} else if (bindParameterCount == 1 &&
				(parameterObject == null || providerMethodParameterTypes[
					(providerContextIndex == null || providerContextIndex == 1) ? 0 : 1]
					.isAssignableFrom(parameterObject.getClass()))) {
				//存在一个非ProviderContext的参数
				sql = invokeProviderMethod(extractProviderMethodArguments(parameterObject));
			} else if (parameterObject instanceof Map) {
				@SuppressWarnings("unchecked")
				//处理Map类型的参数
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
			//解析sql中的${}后生成真正的SqlSource
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

	/**
	 * 提取完整的参数数组
	 */
	private Object[] extractProviderMethodArguments(Object parameterObject) {
		if (providerContext != null) {
			//存在ProviderContext，将ProviderContext和普通参数区分开来
			Object[] args = new Object[2];
			args[providerContextIndex == 0 ? 1 : 0] = parameterObject;
			args[providerContextIndex] = providerContext;
			return args;
		} else {
			//直接转成参数数组返回
			return new Object[]{parameterObject};
		}
	}

	/**
	 * 根据参数名-参数 映射和参数名数组，组合成正确顺序的参数数组
	 *
	 * @param params 参数名-参数映射
	 * @param argumentNames 参数名数组
	 */
	private Object[] extractProviderMethodArguments(Map<String, Object> params,
		String[] argumentNames) {
		//参数数组的总长度
		Object[] args = new Object[argumentNames.length];
		for (int i = 0; i < args.length; i++) {
			//ProviderContext参数需要单独判断
			if (providerContextIndex != null && providerContextIndex == i) {
				args[i] = providerContext;
			} else {
				//普通类型参数直接放
				args[i] = params.get(argumentNames[i]);
			}
		}
		return args;
	}

	/**
	 * 调用sql构建类的构建方法得到sql
	 *
	 * @param args 方法参数
	 */
	private String invokeProviderMethod(Object... args) throws Exception {
		Object targetObject = null;
		if (!Modifier.isStatic(providerMethod.getModifiers())) {
			//非静态创建构建类实例
			targetObject = providerType.newInstance();
		}
		//生成sql
		CharSequence sql = (CharSequence) providerMethod.invoke(targetObject, args);
		//返回sql
		return sql != null ? sql.toString() : null;
	}

	/**
	 * 解析动态占位符${}
	 */
	private String replacePlaceholder(String sql) {
		return PropertyParser.parse(sql, configuration.getVariables());
	}

}
