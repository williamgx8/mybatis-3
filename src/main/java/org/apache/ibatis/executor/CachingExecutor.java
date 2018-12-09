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
package org.apache.ibatis.executor;

import java.sql.SQLException;
import java.util.List;

import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.cache.TransactionalCacheManager;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.mapping.StatementType;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.transaction.Transaction;

/**
 * 二级缓存executor
 *
 * @author Clinton Begin
 * @author Eduardo Macarron
 */
public class CachingExecutor implements Executor {

	//被包装的Executor，BaseExecutor的子类，一级缓存
	private final Executor delegate;
	//创建二级缓存管理对象，支持事务，其中包含了事务缓存
	//一个sqlSession和一个executor一一对应，一个executor中可能包含多个事务，这多个事务都由一个事务缓存管理器管理
	private final TransactionalCacheManager tcm = new TransactionalCacheManager();

	public CachingExecutor(Executor delegate) {
		this.delegate = delegate;
		delegate.setExecutorWrapper(this);
	}

	@Override
	public Transaction getTransaction() {
		return delegate.getTransaction();
	}

	@Override
	public void close(boolean forceRollback) {
		try {
			//issues #499, #524 and #573
			//如果强制回滚回滚
			if (forceRollback) {
				tcm.rollback();
			} else {
				//否则提交
				tcm.commit();
			}
		} finally {
			//包装类关闭
			delegate.close(forceRollback);
		}
	}

	@Override
	public boolean isClosed() {
		return delegate.isClosed();
	}

	@Override
	public int update(MappedStatement ms, Object parameterObject) throws SQLException {
		//是否刷新事务缓存
		flushCacheIfRequired(ms);
		//委托更新
		return delegate.update(ms, parameterObject);
	}

	@Override
	public <E> List<E> query(MappedStatement ms, Object parameterObject, RowBounds rowBounds,
		ResultHandler resultHandler) throws SQLException {
		BoundSql boundSql = ms.getBoundSql(parameterObject);
		CacheKey key = createCacheKey(ms, parameterObject, rowBounds, boundSql);
		return query(ms, parameterObject, rowBounds, resultHandler, key, boundSql);
	}

	@Override
	public <E> Cursor<E> queryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds)
		throws SQLException {
		flushCacheIfRequired(ms);
		return delegate.queryCursor(ms, parameter, rowBounds);
	}

	@Override
	public <E> List<E> query(MappedStatement ms, Object parameterObject, RowBounds rowBounds,
		ResultHandler resultHandler, CacheKey key, BoundSql boundSql)
		throws SQLException {
		//从MappedStatement中得到二级缓存Cache对象，该Cache源头是根据namespace创建的，默认为PerpetualCache和LruCache
		Cache cache = ms.getCache();
		if (cache != null) {
			//是否清空事务缓存
			/**
			 * 为什么清空的是事务缓存，因为，二级缓存是多session共享的，试想一下，如果一个session没有commit，
			 * 只是因为配置了<cache flushCache="true"/>就在每次操作前把二级缓存删了，那么这次session要是回滚
			 * 二级缓存不就没法恢复了？即便不考虑这种情况，如果仅仅一个session的单一操作就要删除所有session共享
			 * 的二级缓存，那二级缓存的使用率又能有多少呢？
			 */
			flushCacheIfRequired(ms);
			if (ms.isUseCache() && resultHandler == null) {
				//确保存储过程没有输出参数
				ensureNoOutParams(ms, boundSql);
				@SuppressWarnings("unchecked")
				//查询二级缓存，查询的是事务缓存但实际上就是二级缓存，从cache中取出key对应的内容
					//因为二级缓存是对应Mapper.xml的，每一个语句标签对应一个二级缓存实体，cache
					//中包含了很多二级缓存实体，找出对应key的那个
					List<E> list = (List<E>) tcm.getObject(cache, key);
				if (list == null) {
					//二级缓存没有才走BaseExecutor的query
					list = delegate.<E>query(ms, parameterObject, rowBounds, resultHandler, key,
						boundSql);
					//放入事务缓存
					tcm.putObject(cache, key, list); // issue #578 and #116
				}
				return list;
			}
		}
		//没有缓存直接调用包装类查询
		return delegate.<E>query(ms, parameterObject, rowBounds, resultHandler, key, boundSql);
	}

	@Override
	public List<BatchResult> flushStatements() throws SQLException {
		return delegate.flushStatements();
	}

	@Override
	public void commit(boolean required) throws SQLException {
		//包装类执行提交
		delegate.commit(required);
		//事务管理提交，主要是让所有的事务缓存提交，此时如果设置了二级缓存刷新，才会真正清除二级缓存
		tcm.commit();
	}

	@Override
	public void rollback(boolean required) throws SQLException {
		try {
			//包装类回滚
			delegate.rollback(required);
		} finally {
			if (required) {
				//事务管理器回滚
				tcm.rollback();
			}
		}
	}

	private void ensureNoOutParams(MappedStatement ms, BoundSql boundSql) {
		if (ms.getStatementType() == StatementType.CALLABLE) {
			for (ParameterMapping parameterMapping : boundSql.getParameterMappings()) {
				if (parameterMapping.getMode() != ParameterMode.IN) {
					throw new ExecutorException(
						"Caching stored procedures with OUT params is not supported.  Please configure useCache=false in "
							+ ms.getId() + " statement.");
				}
			}
		}
	}

	/**
	 * 委派给被包装的BaseExecutor的子类创建cacheKey，从中可以推断，二级缓存和一级缓存使用同一个cache key
	 */
	@Override
	public CacheKey createCacheKey(MappedStatement ms, Object parameterObject, RowBounds rowBounds,
		BoundSql boundSql) {
		return delegate.createCacheKey(ms, parameterObject, rowBounds, boundSql);
	}

	@Override
	public boolean isCached(MappedStatement ms, CacheKey key) {
		return delegate.isCached(ms, key);
	}

	@Override
	public void deferLoad(MappedStatement ms, MetaObject resultObject, String property,
		CacheKey key, Class<?> targetType) {
		delegate.deferLoad(ms, resultObject, property, key, targetType);
	}

	@Override
	public void clearLocalCache() {
		delegate.clearLocalCache();
	}

	/**
	 * 判断将事务缓存清空
	 */
	private void flushCacheIfRequired(MappedStatement ms) {
		//获得二级缓存
		Cache cache = ms.getCache();
		//缓存不为空并且设置了刷新缓存(每次查询都将事务缓存清空)
		if (cache != null && ms.isFlushCacheRequired()) {
			//清空事务缓存，事务缓存是将二级缓存又进行了一次包装
			tcm.clear(cache);
		}
	}

	@Override
	public void setExecutorWrapper(Executor executor) {
		throw new UnsupportedOperationException("This method should not be called");
	}

}
