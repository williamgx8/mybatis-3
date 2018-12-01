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

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.ibatis.io.ResolverUtil;
import org.apache.ibatis.io.Resources;

/**
 * 所有类型的别名存储，不管是Class的，还是类处理器TypeHandler的
 *
 * @author Clinton Begin
 */
public class TypeAliasRegistry {

	private final Map<String, Class<?>> TYPE_ALIASES = new HashMap<>();

	public TypeAliasRegistry() {
		registerAlias("string", String.class);

		registerAlias("byte", Byte.class);
		registerAlias("long", Long.class);
		registerAlias("short", Short.class);
		registerAlias("int", Integer.class);
		registerAlias("integer", Integer.class);
		registerAlias("double", Double.class);
		registerAlias("float", Float.class);
		registerAlias("boolean", Boolean.class);

		registerAlias("byte[]", Byte[].class);
		registerAlias("long[]", Long[].class);
		registerAlias("short[]", Short[].class);
		registerAlias("int[]", Integer[].class);
		registerAlias("integer[]", Integer[].class);
		registerAlias("double[]", Double[].class);
		registerAlias("float[]", Float[].class);
		registerAlias("boolean[]", Boolean[].class);

		registerAlias("_byte", byte.class);
		registerAlias("_long", long.class);
		registerAlias("_short", short.class);
		registerAlias("_int", int.class);
		registerAlias("_integer", int.class);
		registerAlias("_double", double.class);
		registerAlias("_float", float.class);
		registerAlias("_boolean", boolean.class);

		registerAlias("_byte[]", byte[].class);
		registerAlias("_long[]", long[].class);
		registerAlias("_short[]", short[].class);
		registerAlias("_int[]", int[].class);
		registerAlias("_integer[]", int[].class);
		registerAlias("_double[]", double[].class);
		registerAlias("_float[]", float[].class);
		registerAlias("_boolean[]", boolean[].class);

		registerAlias("date", Date.class);
		registerAlias("decimal", BigDecimal.class);
		registerAlias("bigdecimal", BigDecimal.class);
		registerAlias("biginteger", BigInteger.class);
		registerAlias("object", Object.class);

		registerAlias("date[]", Date[].class);
		registerAlias("decimal[]", BigDecimal[].class);
		registerAlias("bigdecimal[]", BigDecimal[].class);
		registerAlias("biginteger[]", BigInteger[].class);
		registerAlias("object[]", Object[].class);

		registerAlias("map", Map.class);
		registerAlias("hashmap", HashMap.class);
		registerAlias("list", List.class);
		registerAlias("arraylist", ArrayList.class);
		registerAlias("collection", Collection.class);
		registerAlias("iterator", Iterator.class);

		registerAlias("ResultSet", ResultSet.class);
	}

	/**
	 * 根据名称获取对应的Class，如果是昵称，返回昵称对应的Class，否则名称就是Class全路径名，直接转
	 *
	 * @param string 别名/Class全路径
	 * @return 对应的Class
	 */
	@SuppressWarnings("unchecked")
	// throws class cast exception as well if types cannot be assigned
	public <T> Class<T> resolveAlias(String string) {
		try {
			if (string == null) {
				return null;
			}
			// issue #748
			String key = string.toLowerCase(Locale.ENGLISH);
			Class<T> value;
			if (TYPE_ALIASES.containsKey(key)) {
				value = (Class<T>) TYPE_ALIASES.get(key);
			} else {
				value = (Class<T>) Resources.classForName(string);
			}
			return value;
		} catch (ClassNotFoundException e) {
			throw new TypeException("Could not resolve type alias '" + string + "'.  Cause: " + e,
				e);
		}
	}

	/**
	 * 扫描包下所有别名
	 *
	 * @param packageName 待扫描的包名
	 */
	public void registerAliases(String packageName) {
		registerAliases(packageName, Object.class);
	}

	public void registerAliases(String packageName, Class<?> superType) {
		ResolverUtil<Class<?>> resolverUtil = new ResolverUtil<>();
		//扫包，解析所有的Class
		resolverUtil.find(new ResolverUtil.IsA(superType), packageName);
		Set<Class<? extends Class<?>>> typeSet = resolverUtil.getClasses();
		for (Class<?> type : typeSet) {
			// Ignore inner classes and interfaces (including package-info.java)
			// Skip also inner classes. See issue #6
			//不是匿名类、接口和成员类（在一个类内部声明的一个非静态类）就进行别名注册
			if (!type.isAnonymousClass() && !type.isInterface() && !type.isMemberClass()) {
				registerAlias(type);
			}
		}
	}

	/**
	 * 添加type的简单类型和其自身的映射，如果该类有@Alias修饰，取得@Alias的value作为别名
	 *
	 * @param type Class类型
	 */
	public void registerAlias(Class<?> type) {
		String alias = type.getSimpleName();
		Alias aliasAnnotation = type.getAnnotation(Alias.class);
		if (aliasAnnotation != null) {
			alias = aliasAnnotation.value();
		}
		registerAlias(alias, type);
	}

	/**
	 * 添加别名和对应Java类型的映射
	 *
	 * @param alias 别名
	 * @param value Java Class
	 */
	public void registerAlias(String alias, Class<?> value) {
		if (alias == null) {
			throw new TypeException("The parameter alias cannot be null");
		}
		// issue #748
		String key = alias.toLowerCase(Locale.ENGLISH);
		//检测是否重复
		if (TYPE_ALIASES.containsKey(key) && TYPE_ALIASES.get(key) != null && !TYPE_ALIASES.get(key)
			.equals(value)) {
			throw new TypeException(
				"The alias '" + alias + "' is already mapped to the value '" + TYPE_ALIASES.get(key)
					.getName() + "'.");
		}
		TYPE_ALIASES.put(key, value);
	}

	/**
	 * 添加别名和对应Java类全路径名Class映射
	 *
	 * @param alias 别名
	 * @param value Java全路径名称
	 */
	public void registerAlias(String alias, String value) {
		try {
			registerAlias(alias, Resources.classForName(value));
		} catch (ClassNotFoundException e) {
			throw new TypeException(
				"Error registering type alias " + alias + " for " + value + ". Cause: " + e, e);
		}
	}

	/**
	 * @since 3.2.2
	 */
	public Map<String, Class<?>> getTypeAliases() {
		return Collections.unmodifiableMap(TYPE_ALIASES);
	}

}
