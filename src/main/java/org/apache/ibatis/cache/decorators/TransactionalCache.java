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
 * 二级缓存的事务缓冲区，事务缓存
 * 在事务没有提交之前对于二级缓存的操作只是对事务缓存的操作，事务提交后事务缓存才会将对二级缓存的变化写入二级缓存
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

	//包装的二级缓存
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
		//从二级缓存中取
		Object object = delegate.getObject(key);
		if (object == null) {
			//没有对应缓存，放入用于标记二级缓存没有命中key的集合中
			entriesMissedInCache.add(key);
		}
		// issue #146
		if (clearOnCommit) {
			//如果每次操作都清空缓存就没必要返回
			return null;
		} else {
			return object;
		}
	}

	@Override
	public ReadWriteLock getReadWriteLock() {
		return null;
	}

	/**
	 * 放入entriesToAddOnCommit中事务提交时才真正放入二级缓存
	 *
	 * @param key Can be any object but usually it is a {@link CacheKey}
	 */
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
		//标记等事务提交后需要清空二级缓存
		clearOnCommit = true;
		//讲缓存暂存空间清空
		entriesToAddOnCommit.clear();
	}

	public void commit() {
		//提交是否需要清除缓存
		if (clearOnCommit) {
			//清除二级缓存
			delegate.clear();
		}
		//将entriesToAddOnCommit中和entriesMissedInCache中的内容刷入二级缓存
		//上面清除缓存表示的是如果设置了flushCache这种每次清空缓存要做的操作，这里
		//是将本次事务对于缓存的操作刷入二级缓存
		flushPendingEntries();
		//将事务缓存所有容器变量清空
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
		//遍历在本次事务开启到事务结束之间二级缓存的变化，依次写入二级缓存
		for (Map.Entry<Object, Object> entry : entriesToAddOnCommit.entrySet()) {
			delegate.putObject(entry.getKey(), entry.getValue());
		}
		//存在在二级缓存中没有查到的，本次事务缓存也没有的，说明该缓存就是没有，为了防止缓存击穿，
		//讲对应key置成null
		for (Object entry : entriesMissedInCache) {
			//本次事务新增的排除
			if (!entriesToAddOnCommit.containsKey(entry)) {
				delegate.putObject(entry, null);
			}
		}
	}

	private void unlockMissedEntries() {
		/**
		 * 将本次事务提交前，其他事物提交了本次查询是null的key的这部分cache删除，为什么要有这个操作，为什么本来就在二级缓存中不存在的
		 * key要再删除一次，其实答案在该方法的调用处rollback()，在回滚时检查missedInCache说明在同一个事务之前的操作中查到该key对应
		 * 的二级缓存不存在，那么再回滚肯定要将本次事务中的操作都还原到事务开始的状态，那么事务开始时，key对应的cache就是不存在的，可能
		 * 在本次事务中其他事物已经向二级缓存中塞入了key对应的数据，所以为了肯定回到本次事务初始的状态必须手动删除一次key对应的二级缓存
		 */

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
