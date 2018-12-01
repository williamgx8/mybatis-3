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

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.cache.CacheException;

/**
 * Simple blocking decorator
 * <p>
 * Simple and inefficient version of EhCache's BlockingCache decorator.
 * It sets a lock over a cache key when the element is not found in cache.
 * This way, other threads will wait until this element is filled instead of hitting the database.
 *
 * @author Eduardo Macarron
 */
public class BlockingCache implements Cache {

	//阻塞等待超时时间
	private long timeout;
	private final Cache delegate;
	/**
	 * 每一个缓存对应一个lock，而不是整个cache共享一个lock，细颗粒度控制
	 * key：缓存key，value：缓存对应的lock
	 */
	private final ConcurrentHashMap<Object, ReentrantLock> locks;

	public BlockingCache(Cache delegate) {
		this.delegate = delegate;
		this.locks = new ConcurrentHashMap<>();
	}

	@Override
	public String getId() {
		return delegate.getId();
	}

	@Override
	public int getSize() {
		return delegate.getSize();
	}

	/**
	 * 如果线程调用putObject设置、更新缓存是不会被阻止的，Mybatis假定了用户一定会先调用
	 * getObject获取缓存，获取不到再设置，如果直接设置可能会导致getObject的前后有变化
	 * （虽然一般不推荐直接设置缓存，但还是存在问题的）
	 *
	 * @param key Can be any object but usually it is a {@link CacheKey}
	 * @param value The result of a select.
	 */
	@Override
	public void putObject(Object key, Object value) {
		try {
			//设置缓存
			delegate.putObject(key, value);
		} finally {
			//释放锁
			releaseLock(key);
		}
	}

	@Override
	public Object getObject(Object key) {
		//获取key对应缓存相关的锁，当前线程尝试获取该锁
		acquireLock(key);
		//抢到锁了查询key对应的缓存
		Object value = delegate.getObject(key);
		//缓存存在释放锁
		if (value != null) {
			releaseLock(key);
		}
		//不存在，下一步该线程肯定要设置，在设置成功这段时间不能缓存击穿，一直握着锁不释放
		return value;
	}

	/**
	 * 并不真正移除缓存而是释放在该缓存上的lock
	 *
	 * @param key The key
	 */
	@Override
	public Object removeObject(Object key) {
		// despite of its name, this method is called only to release locks
		releaseLock(key);
		return null;
	}

	@Override
	public void clear() {
		delegate.clear();
	}

	@Override
	public ReadWriteLock getReadWriteLock() {
		return null;
	}

	/**
	 * 获取缓存key对应的锁对象，如果key没有锁，先创建再返回
	 */
	private ReentrantLock getLockForKey(Object key) {
		ReentrantLock lock = new ReentrantLock();
		ReentrantLock previous = locks.putIfAbsent(key, lock);
		return previous == null ? lock : previous;
	}

	/**
	 * 争抢锁资源
	 */
	private void acquireLock(Object key) {
		//获得锁对象
		Lock lock = getLockForKey(key);
		//有超时时间
		if (timeout > 0) {
			try {
				//带上超时时间抢锁
				boolean acquired = lock.tryLock(timeout, TimeUnit.MILLISECONDS);
				if (!acquired) {
					//超时没抢到
					throw new CacheException(
						"Couldn't get a lock in " + timeout + " for the key " + key
							+ " at the cache " + delegate.getId());
				}
			} catch (InterruptedException e) {
				throw new CacheException(
					"Got interrupted while trying to acquire lock for key " + key, e);
			}
		} else {
			//直接抢，抢不到一直抢
			lock.lock();
		}
	}

	/**
	 * 释放缓存key对应的锁
	 */
	private void releaseLock(Object key) {
		ReentrantLock lock = locks.get(key);
		//要保证锁是被当前线程持有的
		if (lock.isHeldByCurrentThread()) {
			lock.unlock();
		}
	}

	public long getTimeout() {
		return timeout;
	}

	public void setTimeout(long timeout) {
		this.timeout = timeout;
	}
}