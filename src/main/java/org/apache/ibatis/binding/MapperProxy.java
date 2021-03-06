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
package org.apache.ibatis.binding;

import java.io.Serializable;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;

import org.apache.ibatis.reflection.ExceptionUtil;
import org.apache.ibatis.session.SqlSession;

/**
 * Mapper接口的代理对象，因为Mapper接口是不能直接执行的，所以每次openSession之后，
 * 会针对要执行的Mapper创建对应的代理，从而执行其中的方法
 *
 * @author Clinton Begin
 * @author Eduardo Macarron
 */
public class MapperProxy<T> implements InvocationHandler, Serializable {

	private static final long serialVersionUID = -6424540398559729838L;
	//当前会话
	private final SqlSession sqlSession;
	//Mapper类型
	private final Class<T> mapperInterface;
	//方法与其包装类映射
	private final Map<Method, MapperMethod> methodCache;

	public MapperProxy(SqlSession sqlSession, Class<T> mapperInterface,
		Map<Method, MapperMethod> methodCache) {
		this.sqlSession = sqlSession;
		this.mapperInterface = mapperInterface;
		this.methodCache = methodCache;
	}

	@Override
	public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
		try {
			//如果是Object定义的方法直接调用
			if (Object.class.equals(method.getDeclaringClass())) {
				return method.invoke(this, args);
				//如果是接口的default方法
			} else if (isDefaultMethod(method)) {
				return invokeDefaultMethod(proxy, method, args);
			}
		} catch (Throwable t) {
			throw ExceptionUtil.unwrapThrowable(t);
		}
		//缓存方法
		final MapperMethod mapperMethod = cachedMapperMethod(method);
		return mapperMethod.execute(sqlSession, args);
	}

	/**
	 * 将方法包装成MapperMethod，并缓存起来
	 */
	private MapperMethod cachedMapperMethod(Method method) {
		return methodCache.computeIfAbsent(method,
			k -> new MapperMethod(mapperInterface, method, sqlSession.getConfiguration()));
	}

	/**
	 * 调用接口的default方法
	 *
	 * @param proxy 对象实例
	 * @param method default方法
	 * @param args 方法参数
	 */
	private Object invokeDefaultMethod(Object proxy, Method method, Object[] args)
		throws Throwable {
		//反射获取 MethodHandles.Lookup 类第一个参数为Class，第二个参数为int的构造器
		final Constructor<MethodHandles.Lookup> constructor = MethodHandles.Lookup.class
			.getDeclaredConstructor(Class.class, int.class);
		//设置可访问
		if (!constructor.isAccessible()) {
			constructor.setAccessible(true);
		}
		//获取方法声明的Class
		final Class<?> declaringClass = method.getDeclaringClass();
		/**
		 * 首先创建MethodHandles.Lookup实例，该实例指向 declaringClass，也就是真正要执行的Class，
		 * 再获得目标类待执行method的指向Lookup，绑定proxy实例，最后调用，将返回值返回
		 */
		return constructor
			.newInstance(declaringClass,
				MethodHandles.Lookup.PRIVATE | MethodHandles.Lookup.PROTECTED
					| MethodHandles.Lookup.PACKAGE | MethodHandles.Lookup.PUBLIC)
			.unreflectSpecial(method, declaringClass).bindTo(proxy).invokeWithArguments(args);
	}

	/**
	 * 判断jdk1.8之后 default方法
	 * Backport of java.lang.reflect.Method#isDefault()
	 */
	private boolean isDefaultMethod(Method method) {
		return (method.getModifiers()
			& (Modifier.ABSTRACT | Modifier.PUBLIC | Modifier.STATIC)) == Modifier.PUBLIC
			&& method.getDeclaringClass().isInterface();
	}
}
