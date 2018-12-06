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
package org.apache.ibatis.cache;

import java.util.HashMap;
import java.util.Map;

import org.apache.ibatis.cache.decorators.TransactionalCache;

/**
 * 二级缓存管理对象
 *
 * @author Clinton Begin
 */
public class TransactionalCacheManager {

	//key：MappedStatement中的Cache，value：将key的Cache再封装成的TransactionalCache对象
	private final Map<Cache, TransactionalCache> transactionalCaches = new HashMap<>();

	/**
	 * 清除事务缓存
	 */
	public void clear(Cache cache) {
		getTransactionalCache(cache).clear();
	}

	/**
	 * 根据cache找到cache在二级缓存中对应的TransactionalCache，再从中根据key得到真正的缓存数据
	 */
	public Object getObject(Cache cache, CacheKey key) {
		return getTransactionalCache(cache).getObject(key);
	}

	public void putObject(Cache cache, CacheKey key, Object value) {
		//获取/创建
		getTransactionalCache(cache).putObject(key, value);
	}

	/**
	 * 提交所有事务缓存，在提交事务缓存时，会判断是否清除本次SqlSession涉及到的二级缓存，
	 * 要清楚该方法的调用实际，只可能在executor.commit()时，也就是说，如果executor中
	 * 存在多次事务，并不会真正清除二级缓存
	 */
	public void commit() {
		//让所有事务缓存提交
		for (TransactionalCache txCache : transactionalCaches.values()) {
			txCache.commit();
		}
	}

	public void rollback() {
		for (TransactionalCache txCache : transactionalCaches.values()) {
			txCache.rollback();
		}
	}

	/**
	 * 得到cache对应的TransactionalCache
	 */
	private TransactionalCache getTransactionalCache(Cache cache) {
		return transactionalCaches.computeIfAbsent(cache, TransactionalCache::new);
	}

}
