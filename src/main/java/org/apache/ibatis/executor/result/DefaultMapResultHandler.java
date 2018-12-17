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
package org.apache.ibatis.executor.result;

import java.util.Map;

import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.ReflectorFactory;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.reflection.wrapper.ObjectWrapperFactory;
import org.apache.ibatis.session.ResultContext;
import org.apache.ibatis.session.ResultHandler;

/**
 * @author Clinton Begin
 */
public class DefaultMapResultHandler<K, V> implements ResultHandler<V> {

	private final Map<K, V> mappedResults;
	private final String mapKey;
	private final ObjectFactory objectFactory;
	private final ObjectWrapperFactory objectWrapperFactory;
	private final ReflectorFactory reflectorFactory;

	@SuppressWarnings("unchecked")
	public DefaultMapResultHandler(String mapKey, ObjectFactory objectFactory,
		ObjectWrapperFactory objectWrapperFactory, ReflectorFactory reflectorFactory) {
		this.objectFactory = objectFactory;
		this.objectWrapperFactory = objectWrapperFactory;
		this.reflectorFactory = reflectorFactory;
		this.mappedResults = objectFactory.create(Map.class);
		this.mapKey = mapKey;
	}

	@Override
	public void handleResult(ResultContext<? extends V> context) {
		//从结果上下文中得到当前结果
		final V value = context.getResultObject();
		//当前结果元数据
		final MetaObject mo = MetaObject
			.forObject(value, objectFactory, objectWrapperFactory, reflectorFactory);
		// TODO is that assignment always true?
		//从对象中取出配置为map key字段的值
		final K key = (K) mo.getValue(mapKey);
		//key为配置字段的值，value为对象本身
		mappedResults.put(key, value);
	}

	public Map<K, V> getMappedResults() {
		return mappedResults;
	}
}
