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
package org.apache.ibatis.reflection.wrapper;

import java.util.List;

import org.apache.ibatis.reflection.ExceptionUtil;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.ReflectionException;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.reflection.invoker.Invoker;
import org.apache.ibatis.reflection.property.PropertyTokenizer;

/**
 * @author Clinton Begin
 */
public class BeanWrapper extends BaseWrapper {

	//某个实例
	private final Object object;
	//实例对应类的元数据
	private final MetaClass metaClass;

	public BeanWrapper(MetaObject metaObject, Object object) {
		super(metaObject);
		this.object = object;
		this.metaClass = MetaClass.forClass(object.getClass(), metaObject.getReflectorFactory());
	}

	/**
	 * 从实例中得到prop封装属性对应的值
	 */
	@Override
	public Object get(PropertyTokenizer prop) {
		if (prop.getIndex() != null) {
			//处理集合属性，获得集合中所有对象
			Object collection = resolveCollection(prop, object);
			//在集合中找到index的那个
			return getCollectionValue(prop, collection);
		} else {
			//普通类型对象
			return getBeanProperty(prop, object);
		}
	}

	/**
	 * 为实例中某属性设置值
	 *
	 * @param prop 待设置的属性
	 * @param value 要设置的值
	 */
	@Override
	public void set(PropertyTokenizer prop, Object value) {
		if (prop.getIndex() != null) {
			//处理集合属性，获得集合中所有对象
			Object collection = resolveCollection(prop, object);
			//设置集合中特定index位置的值
			setCollectionValue(prop, collection, value);
		} else {
			//设置普通属性
			setBeanProperty(prop, object, value);
		}
	}

	@Override
	public String findProperty(String name, boolean useCamelCaseMapping) {
		return metaClass.findProperty(name, useCamelCaseMapping);
	}

	@Override
	public String[] getGetterNames() {
		return metaClass.getGetterNames();
	}

	@Override
	public String[] getSetterNames() {
		return metaClass.getSetterNames();
	}

	@Override
	public Class<?> getSetterType(String name) {
		PropertyTokenizer prop = new PropertyTokenizer(name);
		if (prop.hasNext()) {
			MetaObject metaValue = metaObject.metaObjectForProperty(prop.getIndexedName());
			if (metaValue == SystemMetaObject.NULL_META_OBJECT) {
				return metaClass.getSetterType(name);
			} else {
				return metaValue.getSetterType(prop.getChildren());
			}
		} else {
			return metaClass.getSetterType(name);
		}
	}

	/**
	 * 获取属性对应getter方法的返回值
	 */
	@Override
	public Class<?> getGetterType(String name) {
		PropertyTokenizer prop = new PropertyTokenizer(name);
		if (prop.hasNext()) {
			//解析属性表达式，封装MetaObject
			MetaObject metaValue = metaObject.metaObjectForProperty(prop.getIndexedName());
			if (metaValue == SystemMetaObject.NULL_META_OBJECT) {
				return metaClass.getGetterType(name);
			} else {
				return metaValue.getGetterType(prop.getChildren());
			}
		} else {
			return metaClass.getGetterType(name);
		}
	}

	/**
	 * 属性表达式对应属性是否存在setter
	 *
	 * @param name 属性表达式
	 */
	@Override
	public boolean hasSetter(String name) {
		PropertyTokenizer prop = new PropertyTokenizer(name);
		if (prop.hasNext()) {
			if (metaClass.hasSetter(prop.getIndexedName())) {
				MetaObject metaValue = metaObject.metaObjectForProperty(prop.getIndexedName());
				if (metaValue == SystemMetaObject.NULL_META_OBJECT) {
					return metaClass.hasSetter(name);
				} else {
					return metaValue.hasSetter(prop.getChildren());
				}
			} else {
				return false;
			}
		} else {
			return metaClass.hasSetter(name);
		}
	}

	/**
	 * 属性表达式是否对应属性是否存在getter
	 */
	@Override
	public boolean hasGetter(String name) {
		PropertyTokenizer prop = new PropertyTokenizer(name);
		if (prop.hasNext()) {
			if (metaClass.hasGetter(prop.getIndexedName())) {
				MetaObject metaValue = metaObject.metaObjectForProperty(prop.getIndexedName());
				if (metaValue == SystemMetaObject.NULL_META_OBJECT) {
					return metaClass.hasGetter(name);
				} else {
					return metaValue.hasGetter(prop.getChildren());
				}
			} else {
				return false;
			}
		} else {
			return metaClass.hasGetter(name);
		}
	}

	/**
	 * 如果存在多层属性时，父属性可能还没有初始化，该方法就是初始化属性表达式的父属性，再将
	 * 父属性封装成MetaObject返回
	 * 比如：属性表达是richType.richField，该方法对应的操作为：此时richType在当前实例中为
	 * null，先创建richType对应的对象RichType的实例对象，将该对象塞入当前对象
	 */
	@Override
	public MetaObject instantiatePropertyValue(String name, PropertyTokenizer prop,
		ObjectFactory objectFactory) {
		MetaObject metaValue;
		//获得父属性setter的参数类型，也就是父属性的类型
		Class<?> type = getSetterType(prop.getName());
		try {
			//创建父属性实例
			Object newObject = objectFactory.create(type);
			metaValue = MetaObject.forObject(newObject, metaObject.getObjectFactory(),
				metaObject.getObjectWrapperFactory(), metaObject.getReflectorFactory());
			//为当前实例设置属性表达式中父属性对应字段的实例
			set(prop, newObject);
		} catch (Exception e) {
			throw new ReflectionException(
				"Cannot set value of property '" + name + "' because '" + name
					+ "' is null and cannot be instantiated on instance of " + type.getName()
					+ ". Cause:" + e.toString(), e);
		}
		return metaValue;
	}

	/**
	 * 获得属性在实例中的值
	 *
	 * @param prop 属性封装对象
	 * @param object 对象实例
	 */
	private Object getBeanProperty(PropertyTokenizer prop, Object object) {
		try {
			Invoker method = metaClass.getGetInvoker(prop.getName());
			try {
				return method.invoke(object, NO_ARGUMENTS);
			} catch (Throwable t) {
				throw ExceptionUtil.unwrapThrowable(t);
			}
		} catch (RuntimeException e) {
			throw e;
		} catch (Throwable t) {
			throw new ReflectionException(
				"Could not get property '" + prop.getName() + "' from " + object.getClass()
					+ ".  Cause: " + t.toString(), t);
		}
	}

	private void setBeanProperty(PropertyTokenizer prop, Object object, Object value) {
		try {
			Invoker method = metaClass.getSetInvoker(prop.getName());
			Object[] params = {value};
			try {
				method.invoke(object, params);
			} catch (Throwable t) {
				throw ExceptionUtil.unwrapThrowable(t);
			}
		} catch (Throwable t) {
			throw new ReflectionException(
				"Could not set property '" + prop.getName() + "' of '" + object.getClass()
					+ "' with value '" + value + "' Cause: " + t.toString(), t);
		}
	}

	/**
	 * 对于普通BeanWrapper，无法封装集合类型数据，所以这个继承自父类的方法固定返回false
	 * @return
	 */
	@Override
	public boolean isCollection() {
		return false;
	}

	@Override
	public void add(Object element) {
		throw new UnsupportedOperationException();
	}

	@Override
	public <E> void addAll(List<E> list) {
		throw new UnsupportedOperationException();
	}

}
