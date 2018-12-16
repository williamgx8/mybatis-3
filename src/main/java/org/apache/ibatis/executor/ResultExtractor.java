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
package org.apache.ibatis.executor;

import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.session.Configuration;

import java.lang.reflect.Array;
import java.util.List;

/**
 * @author Andrew Gustafson
 */
public class ResultExtractor {

	private final Configuration configuration;
	private final ObjectFactory objectFactory;

	public ResultExtractor(Configuration configuration, ObjectFactory objectFactory) {
		this.configuration = configuration;
		this.objectFactory = objectFactory;
	}

	/**
	 * 将List<Object>的结果转换为targetType的结果
	 *
	 * @param list 结果集合
	 * @param targetType 最终的结果类型
	 */
	public Object extractObjectFromList(List<Object> list, Class<?> targetType) {
		Object value = null;
		//目标类型原本就是List子类，直接返回
		if (targetType != null && targetType.isAssignableFrom(list.getClass())) {
			value = list;
		} else if (targetType != null && objectFactory.isCollection(targetType)) {
			//目标类型为Collection子类且不是List子类，
			//创建targetType的空对象
			value = objectFactory.create(targetType);
			//targetType的元对象
			MetaObject metaObject = configuration.newMetaObject(value);
			//将结果塞入元对象
			metaObject.addAll(list);
		} else if (targetType != null && targetType.isArray()) {
			//目标类型为数组
			//获取数组中每个元素的类型
			Class<?> arrayComponentType = targetType.getComponentType();
			//创建都是targetType类型，长度和结果集中结果数量相同的新数组
			Object array = Array.newInstance(arrayComponentType, list.size());
			//如果是基本类型循环塞入数组，无法通过toArray方式，因为其参数要求一个对象集合
			if (arrayComponentType.isPrimitive()) {
				for (int i = 0; i < list.size(); i++) {
					Array.set(array, i, list.get(i));
				}
				value = array;
			} else {
				//引用对象直接toArray
				value = list.toArray((Object[]) array);
			}
		} else {
			//普通对象
			if (list != null && list.size() > 1) {
				//还有多个报错
				throw new ExecutorException(
					"Statement returned more than one row, where no more than one was expected.");
			} else if (list != null && list.size() == 1) {
				//直接取出
				value = list.get(0);
			}
		}
		return value;
	}
}
