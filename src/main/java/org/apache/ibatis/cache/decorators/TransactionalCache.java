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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReadWriteLock;

import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;

/**
 * The 2nd level cache transactional buffer.
 * <p>
 * This class holds all cache entries that are to be added to the 2nd level cache during a Session.
 * Entries are sent to the cache when commit is called or discarded if the Session is rolled back.
 * Blocking cache support has been added. Therefore any get() that returns a cache miss
 * will be followed by a put() so any lock associated with the key can be released.
 *
 * @author Clinton Begin
 * @author Eduardo Macarron
 */
public class TransactionalCache implements Cache {

	private static final Log log = LogFactory.getLog(TransactionalCache.class);

	private final Cache delegate;
	private boolean clearOnCommit;
	private final Map<Object, Object> entriesToAddOnCommit;
	private final Set<Object> entriesMissedInCache;

	public TransactionalCache(Cache delegate) {
		this.delegate = delegate;
		this.clearOnCommit = false;
		this.entriesToAddOnCommit = new HashMap<>();
		this.entriesMissedInCache = new HashSet<>();
	}

	@Override
	public String getId() {
		return delegate.getId();
	}

	@Override
	public int getSize() {
		return delegate.getSize();
	}

	@Override
	public Object getObject(Object key) {
		// issue #116
		Object object = delegate.getObject(key);
		if (object == null) {
			entriesMissedInCache.add(key);
		}
		// issue #146
		if (clearOnCommit) {
			return null;
		} else {
			return object;
		}
	}

	@Override
	public ReadWriteLock getReadWriteLock() {
		return null;
	}

	@Override
	public void putObject(Object key, Object object) {
		entriesToAddOnCommit.put(key, object);
	}

	@Override
	public Object removeObject(Object key) {
		return null;
	}

	@Override
	public void clear() {
		clearOnCommit = true;
		entriesToAddOnCommit.clear();
	}

	public void commit() {
		//提交是否需要清除缓存
		if (clearOnCommit) {
			//清除二级缓存
			delegate.clear();
		}
		//处理本次事务产生的和未命中的cache
		flushPendingEntries();
		//重置本次事务所有缓存信息
		reset();
	}

	public void rollback() {
		unlockMissedEntries();
		reset();
	}

	private void reset() {
		clearOnCommit = false;
		entriesToAddOnCommit.clear();
		entriesMissedInCache.clear();
	}

	private void flushPendingEntries() {
		//将本次事务新增的内容写入二级缓存
		for (Map.Entry<Object, Object> entry : entriesToAddOnCommit.entrySet()) {
			delegate.putObject(entry.getKey(), entry.getValue());
		}
		//本次事务未查询到的缓存放入二级缓存，值为null，不明白有什么意义
		//如果在事务提交前另一个session已经向对应key中写入了内容，此时不就又置null了？
		for (Object entry : entriesMissedInCache) {
			//本次事务新增的排除
			if (!entriesToAddOnCommit.containsKey(entry)) {
				delegate.putObject(entry, null);
			}
		}
	}

	private void unlockMissedEntries() {
		//将本次事务提交前，其他事物提交了本次查询是null的key的这部分cache删除？
		for (Object entry : entriesMissedInCache) {
			try {
				delegate.removeObject(entry);
			} catch (Exception e) {
				log.warn("Unexpected exception while notifiying a rollback to the cache adapter."
					+ "Consider upgrading your cache adapter to the latest version.  Cause: " + e);
			}
		}
	}

}
