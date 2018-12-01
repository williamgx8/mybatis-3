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
package org.apache.ibatis.binding;

import org.apache.ibatis.builder.annotation.MapperAnnotationBuilder;
import org.apache.ibatis.io.ResolverUtil;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * @author Clinton Begin
 * @author Eduardo Macarron
 * @author Lasse Voss
 */
public class MapperRegistry {

	private final Configuration config;
	//Mapper的Class类型和产生Mapper代理工厂的映射
	private final Map<Class<?>, MapperProxyFactory<?>> knownMappers = new HashMap<>();

	public MapperRegistry(Configuration config) {
		this.config = config;
	}

	/**
	 * 获得Mapper的实例
	 *
	 * @param type Mapper类型
	 * @param sqlSession 当前会话
	 * @return Mapper实例
	 */
	@SuppressWarnings("unchecked")
	public <T> T getMapper(Class<T> type, SqlSession sqlSession) {
		//获取Mapper对应Mapper代理工厂
		final MapperProxyFactory<T> mapperProxyFactory = (MapperProxyFactory<T>) knownMappers
			.get(type);
		//不存在报错
		if (mapperProxyFactory == null) {
			throw new BindingException("Type " + type + " is not known to the MapperRegistry.");
		}
		try {
			//创建Mapper实例
			return mapperProxyFactory.newInstance(sqlSession);
		} catch (Exception e) {
			throw new BindingException("Error getting mapper instance. Cause: " + e, e);
		}
	}

	public <T> boolean hasMapper(Class<T> type) {
		return knownMappers.containsKey(type);
	}

	/**
	 * 添加Mapper
	 *
	 * @param type Mapper类型
	 */
	public <T> void addMapper(Class<T> type) {
		if (type.isInterface()) {
			//添加的Mapper不能已经存在
			if (hasMapper(type)) {
				throw new BindingException(
					"Type " + type + " is already known to the MapperRegistry.");
			}
			boolean loadCompleted = false;
			try {
				//将解析到的Mapper放入容器
				knownMappers.put(type, new MapperProxyFactory<T>(type));
				// It's important that the type is added before the parser is run
				// otherwise the binding may automatically be attempted by the
				// mapper parser. If the type is already known, it won't try.
				//封装Mapper注解解释器
				MapperAnnotationBuilder parser = new MapperAnnotationBuilder(config, type);
				//Mapper注解解析
				parser.parse();
				loadCompleted = true;
			} finally {
				//如果解析出问题或者解析未完成，从容器中移除
				if (!loadCompleted) {
					knownMappers.remove(type);
				}
			}
		}
	}

	/**
	 * @since 3.2.2
	 */
	public Collection<Class<?>> getMappers() {
		return Collections.unmodifiableCollection(knownMappers.keySet());
	}

	/**
	 * 扫描包下superType的所有子类的Mapper
	 *
	 * @since 3.2.2
	 */
	public void addMappers(String packageName, Class<?> superType) {
		ResolverUtil<Class<?>> resolverUtil = new ResolverUtil<>();
		resolverUtil.find(new ResolverUtil.IsA(superType), packageName);
		Set<Class<? extends Class<?>>> mapperSet = resolverUtil.getClasses();
		for (Class<?> mapperClass : mapperSet) {
			addMapper(mapperClass);
		}
	}

	/**
	 * 扫描报下所有Mapper
	 *
	 * @since 3.2.2
	 */
	public void addMappers(String packageName) {
		addMappers(packageName, Object.class);
	}

}
