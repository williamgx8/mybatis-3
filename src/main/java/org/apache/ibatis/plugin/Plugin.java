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
package org.apache.ibatis.plugin;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.ibatis.reflection.ExceptionUtil;

/**
 * 该类的两个作用：1.生成目标对象应用插件的代理对象；2.当方法被拦截时处理拦截逻辑
 *
 * @author Clinton Begin
 */
public class Plugin implements InvocationHandler {

	//目标对象
	private final Object target;
	//拦截器
	private final Interceptor interceptor;
	//一个@Intercepts中可能存在的多个@Signature映射
	private final Map<Class<?>, Set<Method>> signatureMap;

	private Plugin(Object target, Interceptor interceptor,
		Map<Class<?>, Set<Method>> signatureMap) {
		this.target = target;
		this.interceptor = interceptor;
		this.signatureMap = signatureMap;
	}

	/**
	 * 应用某拦截器生成对应代理对象
	 *
	 * @param target 目标对象
	 * @param interceptor 拦截器
	 */
	public static Object wrap(Object target, Interceptor interceptor) {
		//解析拦截器上的@Signature
		Map<Class<?>, Set<Method>> signatureMap = getSignatureMap(interceptor);
		//目标类类型
		Class<?> type = target.getClass();
		//获得所有需要拦截的接口
		Class<?>[] interfaces = getAllInterfaces(type, signatureMap);
		if (interfaces.length > 0) {
			//创建目标类的代理，拦截方法处理句柄就是该类本身的实例
			return Proxy.newProxyInstance(
				type.getClassLoader(),
				interfaces,
				new Plugin(target, interceptor, signatureMap));
		}
		return target;
	}

	@Override
	public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
		try {
			//目标类要拦截的方法集合
			Set<Method> methods = signatureMap.get(method.getDeclaringClass());
			//本次方法是需要拦截的
			if (methods != null && methods.contains(method)) {
				//拦截处理，返回代理方法的结果
				return interceptor.intercept(new Invocation(target, method, args));
			}
			//不是需要拦截的方法，直接调用
			return method.invoke(target, args);
		} catch (Exception e) {
			throw ExceptionUtil.unwrapThrowable(e);
		}
	}

	private static Map<Class<?>, Set<Method>> getSignatureMap(Interceptor interceptor) {
		//@Intercepts注解
		Intercepts interceptsAnnotation = interceptor.getClass().getAnnotation(Intercepts.class);
		// issue #251
		//没有该注解报错
		if (interceptsAnnotation == null) {
			throw new PluginException(
				"No @Intercepts annotation was found in interceptor " + interceptor.getClass()
					.getName());
		}
		//注解中所有的@Signature属性
		Signature[] sigs = interceptsAnnotation.value();
		Map<Class<?>, Set<Method>> signatureMap = new HashMap<>();
		//解析每个@Signature
		for (Signature sig : sigs) {
			//需要被拦截的类--类方法映射
			Set<Method> methods = signatureMap.computeIfAbsent(sig.type(), k -> new HashSet<>());
			try {
				//需要被拦截的类中的方法
				Method method = sig.type().getMethod(sig.method(), sig.args());
				//加入类--类方法映射
				methods.add(method);
			} catch (NoSuchMethodException e) {
				throw new PluginException(
					"Could not find method on " + sig.type() + " named " + sig.method()
						+ ". Cause: " + e, e);
			}
		}
		return signatureMap;
	}

	/**
	 * 获得目标类所有实现接口中需要被拦截接口的集合
	 */
	private static Class<?>[] getAllInterfaces(Class<?> type,
		Map<Class<?>, Set<Method>> signatureMap) {
		Set<Class<?>> interfaces = new HashSet<>();
		while (type != null) {
			//目标类所有接口
			for (Class<?> c : type.getInterfaces()) {
				//接口需要被拦截
				if (signatureMap.containsKey(c)) {
					//放入集合
					interfaces.add(c);
				}
			}
			//BFS父类
			type = type.getSuperclass();
		}
		//转成数组返回
		return interfaces.toArray(new Class<?>[interfaces.size()]);
	}

}
