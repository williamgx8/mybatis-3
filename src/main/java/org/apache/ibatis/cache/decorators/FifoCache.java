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
package org.apache.ibatis.cache.decorators;

import java.util.Deque;
import java.util.LinkedList;
import java.util.concurrent.locks.ReadWriteLock;

import org.apache.ibatis.cache.Cache;

/**
 * FIFO (first in, first out) cache decorator
 * 先进先出缓存
 *
 * @author Clinton Begin
 */
public class FifoCache implements Cache {

	private final Cache delegate;
	//双端队列，保存缓存key
	private final Deque<Object> keyList;
	//队列最大值
	private int size;

	public FifoCache(Cache delegate) {
		this.delegate = delegate;
		this.keyList = new LinkedList<>();
		this.size = 1024;
	}

	@Override
	public String getId() {
		return delegate.getId();
	}

	@Override
	public int getSize() {
		return delegate.getSize();
	}

	public void setSize(int size) {
		this.size = size;
	}

	@Override
	public void putObject(Object key, Object value) {
		//判断缓存是否满了
		cycleKeyList(key);
		delegate.putObject(key, value);
	}

	@Override
	public Object getObject(Object key) {
		return delegate.getObject(key);
	}

	@Override
	public Object removeObject(Object key) {
		return delegate.removeObject(key);
	}

	@Override
	public void clear() {
		delegate.clear();
		//清除缓存的同时清除缓存key队列
		keyList.clear();
	}

	@Override
	public ReadWriteLock getReadWriteLock() {
		return null;
	}

	private void cycleKeyList(Object key) {
		//先把新的加进去
		keyList.addLast(key);
		//缓存总数大于队列规定的最大值
		if (keyList.size() > size) {
			//移除第一个缓存对应的key
			Object oldestKey = keyList.removeFirst();
			//移除key对应的缓存
			delegate.removeObject(oldestKey);
		}
	}

}
