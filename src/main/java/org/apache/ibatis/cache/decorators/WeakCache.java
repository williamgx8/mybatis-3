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

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Deque;
import java.util.LinkedList;
import java.util.concurrent.locks.ReadWriteLock;

import org.apache.ibatis.cache.Cache;

/**
 * Weak Reference cache decorator.
 * Thanks to Dr. Heinz Kabutz for his guidance here.
 *
 * @author Clinton Begin
 */
public class WeakCache implements Cache {

	private final Deque<Object> hardLinksToAvoidGarbageCollection;
	//引用队列，GC后被回收的WeakEntry会放入该队列中，从而可以根据该队列中数据真正删除Cache对应的数据
	private final ReferenceQueue<Object> queueOfGarbageCollectedEntries;
	//被包装的缓存对象，真正的缓存是WeakEntry对象
	private final Cache delegate;
	//cache最大数量
	private int numberOfHardLinks;

	public WeakCache(Cache delegate) {
		this.delegate = delegate;
		this.numberOfHardLinks = 256;
		this.hardLinksToAvoidGarbageCollection = new LinkedList<>();
		this.queueOfGarbageCollectedEntries = new ReferenceQueue<>();
	}

	@Override
	public String getId() {
		return delegate.getId();
	}

	@Override
	public int getSize() {
		/**
		 * 获取cache内数量时，可能存在着刚被GC但没有从cache中移除的内容，所以先检查queueOfGarbageCollectedEntries
		 * 确保在GC回收队列中的内容都已经在cache中被删除
		 */
		removeGarbageCollectedItems();
		return delegate.getSize();
	}

	public void setSize(int size) {
		this.numberOfHardLinks = size;
	}

	@Override
	public void putObject(Object key, Object value) {
		//清除已被GC的cache
		removeGarbageCollectedItems();
		//将key-value封装成WeakEntry，放入缓存
		delegate.putObject(key, new WeakEntry(key, value, queueOfGarbageCollectedEntries));
	}

	@Override
	public Object getObject(Object key) {
		Object result = null;
		@SuppressWarnings("unchecked") // assumed delegate cache is totally managed by this cache
			//从cache中拿
			WeakReference<Object> weakReference = (WeakReference<Object>) delegate.getObject(key);
		if (weakReference != null) {
			//获得WeakReference中真正的缓存内容
			result = weakReference.get();
			//引用为null，说明已经被GC回收了，但没有从cache中删除
			if (result == null) {
				//删除cache
				delegate.removeObject(key);
			} else {
				/**
				 * 相当于又加了一个强引用指向result，此时就有两种引用的指向
				 * 1. WeakReference的弱引用指向
				 * 2. hardLinksToAvoidGarbageCollection的强引用指向
				 * 此时就会丧失弱引用自动GC的作用，不清楚为什么这么做
				 */
				hardLinksToAvoidGarbageCollection.addFirst(result);
				if (hardLinksToAvoidGarbageCollection.size() > numberOfHardLinks) {
					hardLinksToAvoidGarbageCollection.removeLast();
				}
			}
		}
		return result;
	}

	@Override
	public Object removeObject(Object key) {
		removeGarbageCollectedItems();
		return delegate.removeObject(key);
	}

	/**
	 * 清空所有数据
	 */
	@Override
	public void clear() {
		hardLinksToAvoidGarbageCollection.clear();
		removeGarbageCollectedItems();
		delegate.clear();
	}

	@Override
	public ReadWriteLock getReadWriteLock() {
		return null;
	}

	/**
	 * 移除所有在GC回收队列中但还没有被从cache中移除的内容
	 */
	private void removeGarbageCollectedItems() {
		WeakEntry sv;
		while ((sv = (WeakEntry) queueOfGarbageCollectedEntries.poll()) != null) {
			delegate.removeObject(sv.key);
		}
	}

	/**
	 * 继承自弱引用，每次GC都会回收不可达对象，并将回收的对象放入GC引用队列中
	 */
	private static class WeakEntry extends WeakReference<Object> {

		private final Object key;

		/**
		 * 缓存回收的大体流程：
		 * GC-->回收对象--->放入garbageCollectionQueue--->cache中被回收对象的key指向null
		 * --->等待真正从cache中移除
		 *
		 * @param key 键
		 * @param value 值
		 * @param garbageCollectionQueue GC引用队列
		 */
		private WeakEntry(Object key, Object value, ReferenceQueue<Object> garbageCollectionQueue) {
			super(value, garbageCollectionQueue);
			this.key = key;
		}
	}

}
