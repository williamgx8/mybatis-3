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
package org.apache.ibatis.cache.decorators;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;

import org.apache.ibatis.cache.Cache;

/**
 * 支持LRU的cache
 * Lru (least recently used) cache decorator
 *
 * @author Clinton Begin
 */
public class LruCache implements Cache {

	//包装的缓存对象
	private final Cache delegate;
	//实现LRU的容器，key和value实际上都为key，因为keyMap只是记录顺序，从中按序拿到key后真正的value再从包装的cache对象中获得
	private Map<Object, Object> keyMap;
	private Object eldestKey;

	public LruCache(Cache delegate) {
		this.delegate = delegate;
		setSize(1024);
	}

	@Override
	public String getId() {
		return delegate.getId();
	}

	@Override
	public int getSize() {
		return delegate.getSize();
	}

	public void setSize(final int size) {
		/**
		 * 使用LinkedHashMap完成LRU
		 * size：初始大小
		 * loadFactor：扩容因子
		 * accessOrder：true表示按照访问顺序，越近被访问的越靠近队头，false表示按照插入的顺序
		 */
		keyMap = new LinkedHashMap<Object, Object>(size, .75F, true) {
			private static final long serialVersionUID = 4267176411845948333L;

			/**
			 * 重写LinkedHashMap中的removeEldestEntry，该方法会在每次插入新元素的时候调用
			 * @param eldest 最久远的那个元素entry
			 * @return 是否需要被移除
			 */
			@Override
			protected boolean removeEldestEntry(Map.Entry<Object, Object> eldest) {
				boolean tooBig = size() > size;
				//超过数量上限，记录最老的key
				if (tooBig) {
					eldestKey = eldest.getKey();
				}
				return tooBig;
			}
		};
	}

	@Override
	public void putObject(Object key, Object value) {
		//先放入缓存
		delegate.putObject(key, value);
		//然后整理缓存
		cycleKeyList(key);
	}

	@Override
	public Object getObject(Object key) {
		//从LRU容器中拿出key，自动调整下顺序
		keyMap.get(key); //touch
		//获取缓存中真正的内容
		return delegate.getObject(key);
	}

	@Override
	public Object removeObject(Object key) {
		return delegate.removeObject(key);
	}

	/**
	 * 缓存和LRU容器都需要清空
	 */
	@Override
	public void clear() {
		delegate.clear();
		keyMap.clear();
	}

	@Override
	public ReadWriteLock getReadWriteLock() {
		return null;
	}

	private void cycleKeyList(Object key) {
		keyMap.put(key, key);
		//如果缓存数量超过上限，eldestKey就肯定有值，把该值对应的value从cache中移除
		if (eldestKey != null) {
			delegate.removeObject(eldestKey);
			eldestKey = null;
		}
	}

}
