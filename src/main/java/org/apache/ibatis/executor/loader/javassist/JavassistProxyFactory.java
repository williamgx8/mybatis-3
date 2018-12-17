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
package org.apache.ibatis.executor.loader.javassist;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import javassist.util.proxy.MethodHandler;
import javassist.util.proxy.Proxy;
import javassist.util.proxy.ProxyFactory;

import org.apache.ibatis.executor.ExecutorException;
import org.apache.ibatis.executor.loader.AbstractEnhancedDeserializationProxy;
import org.apache.ibatis.executor.loader.AbstractSerialStateHolder;
import org.apache.ibatis.executor.loader.ResultLoaderMap;
import org.apache.ibatis.executor.loader.WriteReplaceInterface;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;
import org.apache.ibatis.reflection.ExceptionUtil;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.reflection.property.PropertyCopier;
import org.apache.ibatis.reflection.property.PropertyNamer;
import org.apache.ibatis.session.Configuration;

/**
 * @author Eduardo Macarron
 */
public class JavassistProxyFactory implements org.apache.ibatis.executor.loader.ProxyFactory {

	private static final Log log = LogFactory.getLog(JavassistProxyFactory.class);
	private static final String FINALIZE_METHOD = "finalize";
	private static final String WRITE_REPLACE_METHOD = "writeReplace";

	public JavassistProxyFactory() {
		try {
			Resources.classForName("javassist.util.proxy.ProxyFactory");
		} catch (Throwable e) {
			throw new IllegalStateException(
				"Cannot enable lazy loading because Javassist is not available. Add Javassist to your classpath.",
				e);
		}
	}

	@Override
	public Object createProxy(Object target, ResultLoaderMap lazyLoader,
		Configuration configuration, ObjectFactory objectFactory,
		List<Class<?>> constructorArgTypes, List<Object> constructorArgs) {
		return EnhancedResultObjectProxyImpl
			.createProxy(target, lazyLoader, configuration, objectFactory, constructorArgTypes,
				constructorArgs);
	}

	/**
	 * 创建支持反序列化的代理对象
	 */
	public Object createDeserializationProxy(Object target,
		Map<String, ResultLoaderMap.LoadPair> unloadedProperties, ObjectFactory objectFactory,
		List<Class<?>> constructorArgTypes, List<Object> constructorArgs) {
		return EnhancedDeserializationProxyImpl
			.createProxy(target, unloadedProperties, objectFactory, constructorArgTypes,
				constructorArgs);
	}

	@Override
	public void setProperties(Properties properties) {
		// Not Implemented
	}

	/**
	 * 创建代理
	 */
	static Object crateProxy(Class<?> type, MethodHandler callback,
		List<Class<?>> constructorArgTypes, List<Object> constructorArgs) {

		//创建 javassist 的proxy factory
		ProxyFactory enhancer = new ProxyFactory();
		//父类，目标对象，代理对象为目标对象子类
		enhancer.setSuperclass(type);

		try {
			//获得类的 Object writeReplace()方法，该方法在序列化到磁盘时可以用于掉包要序列化的内容
			type.getDeclaredMethod(WRITE_REPLACE_METHOD);
			// ObjectOutputStream will call writeReplace of objects returned by writeReplace
			if (log.isDebugEnabled()) {
				log.debug(WRITE_REPLACE_METHOD + " method was found on bean " + type
					+ ", make sure it returns this");
			}
		} catch (NoSuchMethodException e) {
			//报错说明目标类没有实现Serialized接口，那么代理类设置实现WriteReplaceInterfaces
			enhancer.setInterfaces(new Class[]{WriteReplaceInterface.class});
		} catch (SecurityException e) {
			// nothing to do here
		}

		Object enhanced;
		//目标对象构造器类型数组和构造器参数数组都复制一份作为代理对象创建的依据
		Class<?>[] typesArray = constructorArgTypes.toArray(new Class[constructorArgTypes.size()]);
		Object[] valuesArray = constructorArgs.toArray(new Object[constructorArgs.size()]);
		try {
			//创建增强代理对象
			enhanced = enhancer.create(typesArray, valuesArray);
		} catch (Exception e) {
			throw new ExecutorException("Error creating lazy proxy.  Cause: " + e, e);
		}
		//设置代理方法的处理句柄
		((Proxy) enhanced).setHandler(callback);
		return enhanced;
	}

	private static class EnhancedResultObjectProxyImpl implements MethodHandler {

		//目标对象类型
		private final Class<?> type;
		//延迟加载器映射
		private final ResultLoaderMap lazyLoader;
		//是否立即加载
		private final boolean aggressive;
		//延迟加载触发方法名列表，在名称列表中的方法会触发立即查询所有延迟加载内容
		private final Set<String> lazyLoadTriggerMethods;
		//对象工厂
		private final ObjectFactory objectFactory;
		private final List<Class<?>> constructorArgTypes;
		private final List<Object> constructorArgs;

		private EnhancedResultObjectProxyImpl(Class<?> type, ResultLoaderMap lazyLoader,
			Configuration configuration, ObjectFactory objectFactory,
			List<Class<?>> constructorArgTypes, List<Object> constructorArgs) {
			this.type = type;
			this.lazyLoader = lazyLoader;
			this.aggressive = configuration.isAggressiveLazyLoading();
			this.lazyLoadTriggerMethods = configuration.getLazyLoadTriggerMethods();
			this.objectFactory = objectFactory;
			this.constructorArgTypes = constructorArgTypes;
			this.constructorArgs = constructorArgs;
		}

		public static Object createProxy(Object target, ResultLoaderMap lazyLoader,
			Configuration configuration, ObjectFactory objectFactory,
			List<Class<?>> constructorArgTypes, List<Object> constructorArgs) {
			//目标对象
			final Class<?> type = target.getClass();
			//代理方法的具体执行对象
			EnhancedResultObjectProxyImpl callback = new EnhancedResultObjectProxyImpl(type,
				lazyLoader, configuration, objectFactory, constructorArgTypes, constructorArgs);
			//创建代理
			Object enhanced = crateProxy(type, callback, constructorArgTypes, constructorArgs);
			//复制目标对象属性值
			PropertyCopier.copyBeanProperties(type, target, enhanced);
			return enhanced;
		}

		/**
		 *
		 */
		@Override
		public Object invoke(Object enhanced, Method method, Method methodProxy, Object[] args)
			throws Throwable {
			final String methodName = method.getName();
			try {
				//同一时刻一个jvm只能对一个延迟加载映射进行操作
				synchronized (lazyLoader) {
					if (WRITE_REPLACE_METHOD.equals(methodName)) {
						Object original;
						if (constructorArgTypes.isEmpty()) {
							original = objectFactory.create(type);
						} else {
							original = objectFactory
								.create(type, constructorArgTypes, constructorArgs);
						}
						PropertyCopier.copyBeanProperties(type, enhanced, original);
						if (lazyLoader.size() > 0) {
							return new JavassistSerialStateHolder(original,
								lazyLoader.getProperties(), objectFactory, constructorArgTypes,
								constructorArgs);
						} else {
							return original;
						}
					} else {
						//延迟加载映射中存在内容，方法非finalize
						if (lazyLoader.size() > 0 && !FINALIZE_METHOD.equals(methodName)) {
							//如果设置了立即加载或者方法名在触发加载列表中
							if (aggressive || lazyLoadTriggerMethods.contains(methodName)) {
								//加载所有
								lazyLoader.loadAll();
							}
							//setter方法
							else if (PropertyNamer.isSetter(methodName)) {
								//获得setter对应的属性
								final String property = PropertyNamer.methodToProperty(methodName);
								//setter说明已经设置了该值，从延迟加载映射中移除
								lazyLoader.remove(property);
							}
							//getter
							else if (PropertyNamer.isGetter(methodName)) {
								//获得getter对应的属性
								final String property = PropertyNamer.methodToProperty(methodName);
								//是否在延迟加载映射队列中
								//延迟加载的是某个对象中的某个属性，这个属性是存在延迟加载队列映射中的
								if (lazyLoader.hasLoader(property)) {
									//加载
									lazyLoader.load(property);
								}
							}
						}
					}
				}
				//执行原方法
				return methodProxy.invoke(enhanced, args);
			} catch (Throwable t) {
				throw ExceptionUtil.unwrapThrowable(t);
			}
		}
	}

	private static class EnhancedDeserializationProxyImpl extends
		AbstractEnhancedDeserializationProxy implements MethodHandler {

		private EnhancedDeserializationProxyImpl(Class<?> type,
			Map<String, ResultLoaderMap.LoadPair> unloadedProperties, ObjectFactory objectFactory,
			List<Class<?>> constructorArgTypes, List<Object> constructorArgs) {
			super(type, unloadedProperties, objectFactory, constructorArgTypes, constructorArgs);
		}

		public static Object createProxy(Object target,
			Map<String, ResultLoaderMap.LoadPair> unloadedProperties, ObjectFactory objectFactory,
			List<Class<?>> constructorArgTypes, List<Object> constructorArgs) {
			final Class<?> type = target.getClass();
			EnhancedDeserializationProxyImpl callback = new EnhancedDeserializationProxyImpl(type,
				unloadedProperties, objectFactory, constructorArgTypes, constructorArgs);
			Object enhanced = crateProxy(type, callback, constructorArgTypes, constructorArgs);
			PropertyCopier.copyBeanProperties(type, target, enhanced);
			return enhanced;
		}

		@Override
		public Object invoke(Object enhanced, Method method, Method methodProxy, Object[] args)
			throws Throwable {
			final Object o = super.invoke(enhanced, method, args);
			return o instanceof AbstractSerialStateHolder ? o : methodProxy.invoke(o, args);
		}

		@Override
		protected AbstractSerialStateHolder newSerialStateHolder(Object userBean,
			Map<String, ResultLoaderMap.LoadPair> unloadedProperties, ObjectFactory objectFactory,
			List<Class<?>> constructorArgTypes, List<Object> constructorArgs) {
			return new JavassistSerialStateHolder(userBean, unloadedProperties, objectFactory,
				constructorArgTypes, constructorArgs);
		}
	}
}
