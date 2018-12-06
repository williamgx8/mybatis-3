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

import static org.apache.ibatis.executor.ExecutionPlaceholder.EXECUTION_PLACEHOLDER;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.cache.impl.PerpetualCache;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.statement.StatementUtil;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;
import org.apache.ibatis.logging.jdbc.ConnectionLogger;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.mapping.StatementType;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.transaction.Transaction;
import org.apache.ibatis.type.TypeHandlerRegistry;

/**
 * @author Clinton Begin
 */
public abstract class BaseExecutor implements Executor {

	private static final Log log = LogFactory.getLog(BaseExecutor.class);

	//事务对象
	protected Transaction transaction;
	//包装的Executor
	protected Executor wrapper;
	//延迟加载队列
	protected ConcurrentLinkedQueue<DeferredLoad> deferredLoads;
	//一级缓存
	protected PerpetualCache localCache;
	//输出参数缓存
	protected PerpetualCache localOutputParameterCache;
	protected Configuration configuration;
	//记录嵌套查询的层级
	protected int queryStack;
	//是否关闭
	private boolean closed;

	protected BaseExecutor(Configuration configuration, Transaction transaction) {
		this.transaction = transaction;
		this.deferredLoads = new ConcurrentLinkedQueue<>();
		this.localCache = new PerpetualCache("LocalCache");
		this.localOutputParameterCache = new PerpetualCache("LocalOutputParameterCache");
		this.closed = false;
		this.configuration = configuration;
		this.wrapper = this;
	}

	@Override
	public Transaction getTransaction() {
		if (closed) {
			throw new ExecutorException("Executor was closed.");
		}
		return transaction;
	}

	@Override
	public void close(boolean forceRollback) {
		try {
			try {
				//根据forceRollback判断是否需要回滚
				rollback(forceRollback);
			} finally {
				//关闭事务
				if (transaction != null) {
					transaction.close();
				}
			}
		} catch (SQLException e) {
			// Ignore.  There's nothing that can be done at this point.
			log.warn("Unexpected exception on closing transaction.  Cause: " + e);
		} finally {
			//GC helper
			transaction = null;
			deferredLoads = null;
			localCache = null;
			localOutputParameterCache = null;
			closed = true;
		}
	}

	@Override
	public boolean isClosed() {
		return closed;
	}

	@Override
	public int update(MappedStatement ms, Object parameter) throws SQLException {
		//ms.getResource是Mapper.xml的全路径
		ErrorContext.instance().resource(ms.getResource()).activity("executing an update")
			.object(ms.getId());
		if (closed) {
			//会话已关闭报错
			throw new ExecutorException("Executor was closed.");
		}
		//按照缓存的套路来说，更新操作应该先更新db，然后缓存失效，但由于Mybatis的一级缓存是和每一次会话绑定的
		//先更新缓存再更新db的缓存击穿和脏数据对其他请求没有影响，所以可以这么做
		clearLocalCache();
		//更新db
		return doUpdate(ms, parameter);
	}

	@Override
	public List<BatchResult> flushStatements() throws SQLException {
		return flushStatements(false);
	}

	public List<BatchResult> flushStatements(boolean isRollBack) throws SQLException {
		if (closed) {
			throw new ExecutorException("Executor was closed.");
		}
		return doFlushStatements(isRollBack);
	}

	@Override
	public <E> List<E> query(MappedStatement ms, Object parameter, RowBounds rowBounds,
		ResultHandler resultHandler) throws SQLException {
		//BoundSql对象
		BoundSql boundSql = ms.getBoundSql(parameter);
		//创建一级缓存
		CacheKey key = createCacheKey(ms, parameter, rowBounds, boundSql);
		//查询
		return query(ms, parameter, rowBounds, resultHandler, key, boundSql);
	}

	@SuppressWarnings("unchecked")
	@Override
	public <E> List<E> query(MappedStatement ms, Object parameter, RowBounds rowBounds,
		ResultHandler resultHandler, CacheKey key, BoundSql boundSql) throws SQLException {
		//异常上下文，每一个会话只有一个异常上下文（第一次创建后放在ThreadLocal中）,逐层记录
		//当中间某一层出现异常，exception会向上抛出，在最上层取出异常上下文中内容，就可以知道哪一层什么参数导致了什么异常
		ErrorContext.instance().resource(ms.getResource()).activity("executing a query")
			.object(ms.getId());
		if (closed) {
			//会话已关闭报错
			throw new ExecutorException("Executor was closed.");
		}
		//无嵌套查询且配置的缓存刷新
		if (queryStack == 0 && ms.isFlushCacheRequired()) {
			//清除缓存
			clearLocalCache();
		}
		List<E> list;
		try {
			queryStack++;
			//先从一级缓存中获取
			list = resultHandler == null ? (List<E>) localCache.getObject(key) : null;
			if (list != null) {
				//处理本地缓存（只针对Callable存储过程）
				handleLocallyCachedOutputParameters(ms, key, parameter, boundSql);
			} else {
				//缓存获取失败查询DB
				list = queryFromDatabase(ms, parameter, rowBounds, resultHandler, key, boundSql);
			}
		} finally {
			queryStack--;
		}
		if (queryStack == 0) {
			//处理延迟加载队列，进行加载
			for (DeferredLoad deferredLoad : deferredLoads) {
				deferredLoad.load();
			}
			// issue #601
			//清空延迟加载队列
			deferredLoads.clear();
			//如果一级缓存作用域为一次查询语句，清除缓存，默认为会话session级别
			if (configuration.getLocalCacheScope() == LocalCacheScope.STATEMENT) {
				// issue #482
				clearLocalCache();
			}
		}
		return list;
	}

	@Override
	public <E> Cursor<E> queryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds)
		throws SQLException {
		//创建BoundSql
		BoundSql boundSql = ms.getBoundSql(parameter);
		//查询
		return doQueryCursor(ms, parameter, rowBounds, boundSql);
	}

	@Override
	public void deferLoad(MappedStatement ms, MetaObject resultObject, String property,
		CacheKey key, Class<?> targetType) {
		if (closed) {
			throw new ExecutorException("Executor was closed.");
		}
		DeferredLoad deferredLoad = new DeferredLoad(resultObject, property, key, localCache,
			configuration, targetType);
		if (deferredLoad.canLoad()) {
			deferredLoad.load();
		} else {
			deferredLoads.add(
				new DeferredLoad(resultObject, property, key, localCache, configuration,
					targetType));
		}
	}

	/**
	 * 创建缓存Key
	 */
	@Override
	public CacheKey createCacheKey(MappedStatement ms, Object parameterObject, RowBounds rowBounds,
		BoundSql boundSql) {
		if (closed) {
			throw new ExecutorException("Executor was closed.");
		}
		CacheKey cacheKey = new CacheKey();
		cacheKey.update(ms.getId());
		//添加分页的offset计算
		cacheKey.update(rowBounds.getOffset());
		//添加分页的limit计算
		cacheKey.update(rowBounds.getLimit());
		//添加原始的sql计算
		cacheKey.update(boundSql.getSql());
		//参数映射列表
		List<ParameterMapping> parameterMappings = boundSql.getParameterMappings();
		TypeHandlerRegistry typeHandlerRegistry = ms.getConfiguration().getTypeHandlerRegistry();
		// mimic DefaultParameterHandler logic
		for (ParameterMapping parameterMapping : parameterMappings) {
			if (parameterMapping.getMode() != ParameterMode.OUT) {
				Object value;
				String propertyName = parameterMapping.getProperty();
				if (boundSql.hasAdditionalParameter(propertyName)) {
					value = boundSql.getAdditionalParameter(propertyName);
				} else if (parameterObject == null) {
					value = null;
				} else if (typeHandlerRegistry.hasTypeHandler(parameterObject.getClass())) {
					value = parameterObject;
				} else {
					MetaObject metaObject = configuration.newMetaObject(parameterObject);
					value = metaObject.getValue(propertyName);
				}
				//添加参数值计算
				cacheKey.update(value);
			}
		}
		if (configuration.getEnvironment() != null) {
			// issue #176
			//添加环境标识计算
			cacheKey.update(configuration.getEnvironment().getId());
		}
		return cacheKey;
	}

	/**
	 * 键为key的一级缓存是否存在
	 */
	@Override
	public boolean isCached(MappedStatement ms, CacheKey key) {
		return localCache.getObject(key) != null;
	}

	@Override
	public void commit(boolean required) throws SQLException {
		if (closed) {
			throw new ExecutorException("Cannot commit, transaction is already closed");
		}
		//清除一级缓存
		clearLocalCache();
		//刷入批处理
		flushStatements();
		if (required) {
			//提交事务
			transaction.commit();
		}
	}

	@Override
	public void rollback(boolean required) throws SQLException {
		if (!closed) {
			try {
				//清除一级缓存
				clearLocalCache();
				//刷入批处理
				flushStatements(true);
			} finally {
				if (required) {
					//回滚事务
					transaction.rollback();
				}
			}
		}
	}

	@Override
	public void clearLocalCache() {
		if (!closed) {
			//清除一级缓存
			localCache.clear();
			//清除输出参数缓存
			localOutputParameterCache.clear();
		}
	}

	protected abstract int doUpdate(MappedStatement ms, Object parameter)
		throws SQLException;

	protected abstract List<BatchResult> doFlushStatements(boolean isRollback)
		throws SQLException;

	protected abstract <E> List<E> doQuery(MappedStatement ms, Object parameter,
		RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql)
		throws SQLException;

	protected abstract <E> Cursor<E> doQueryCursor(MappedStatement ms, Object parameter,
		RowBounds rowBounds, BoundSql boundSql)
		throws SQLException;

	/**
	 * 设置事务超时时间
	 * Apply a transaction timeout.
	 *
	 * @param statement a current statement
	 * @throws SQLException if a database access error occurs, this method is called on a closed <code>Statement</code>
	 * @see StatementUtil#applyTransactionTimeout(Statement, Integer, Integer)
	 * @since 3.4.0
	 */
	protected void applyTransactionTimeout(Statement statement) throws SQLException {
		StatementUtil.applyTransactionTimeout(statement, statement.getQueryTimeout(),
			transaction.getTimeout());
	}

	/**
	 * 关闭statement
	 */
	protected void closeStatement(Statement statement) {
		if (statement != null) {
			try {
				if (!statement.isClosed()) {
					//由具体的数据库驱动实现
					statement.close();
				}
			} catch (SQLException e) {
				// ignore
			}
		}
	}

	private void handleLocallyCachedOutputParameters(MappedStatement ms, CacheKey key,
		Object parameter, BoundSql boundSql) {
		if (ms.getStatementType() == StatementType.CALLABLE) {
			final Object cachedParameter = localOutputParameterCache.getObject(key);
			if (cachedParameter != null && parameter != null) {
				final MetaObject metaCachedParameter = configuration.newMetaObject(cachedParameter);
				final MetaObject metaParameter = configuration.newMetaObject(parameter);
				for (ParameterMapping parameterMapping : boundSql.getParameterMappings()) {
					if (parameterMapping.getMode() != ParameterMode.IN) {
						final String parameterName = parameterMapping.getProperty();
						final Object cachedValue = metaCachedParameter.getValue(parameterName);
						metaParameter.setValue(parameterName, cachedValue);
					}
				}
			}
		}
	}

	private <E> List<E> queryFromDatabase(MappedStatement ms, Object parameter, RowBounds rowBounds,
		ResultHandler resultHandler, CacheKey key, BoundSql boundSql) throws SQLException {
		List<E> list;
		//往缓存中放一个占位符，该标识会使得延迟加载不执行
		//可以理解为一个锁，使得多个statement查询时只有一个statement能从数据库查询，其他不会击穿
		localCache.putObject(key, EXECUTION_PLACEHOLDER);
		try {
			//执行数据库查询
			list = doQuery(ms, parameter, rowBounds, resultHandler, boundSql);
		} finally {
			//把占位符删了
			localCache.removeObject(key);
		}
		//把从数据库查询的值放入缓存
		localCache.putObject(key, list);
		if (ms.getStatementType() == StatementType.CALLABLE) {
			//存储过程处理，不关心
			localOutputParameterCache.putObject(key, parameter);
		}
		return list;
	}

	protected Connection getConnection(Log statementLog) throws SQLException {
		//从当前事务中得到连接
		Connection connection = transaction.getConnection();
		//如果statement级别日志开始debug，将连接增强成带更多日志输出的连接，返回
		if (statementLog.isDebugEnabled()) {
			//动态代理返回增强的连接
			return ConnectionLogger.newInstance(connection, statementLog, queryStack);
		} else {
			return connection;
		}
	}

	@Override
	public void setExecutorWrapper(Executor wrapper) {
		this.wrapper = wrapper;
	}

	private static class DeferredLoad {

		private final MetaObject resultObject;
		private final String property;
		private final Class<?> targetType;
		private final CacheKey key;
		private final PerpetualCache localCache;
		private final ObjectFactory objectFactory;
		private final ResultExtractor resultExtractor;

		// issue #781
		public DeferredLoad(MetaObject resultObject,
			String property,
			CacheKey key,
			PerpetualCache localCache,
			Configuration configuration,
			Class<?> targetType) {
			this.resultObject = resultObject;
			this.property = property;
			this.key = key;
			this.localCache = localCache;
			this.objectFactory = configuration.getObjectFactory();
			this.resultExtractor = new ResultExtractor(configuration, objectFactory);
			this.targetType = targetType;
		}

		public boolean canLoad() {
			return localCache.getObject(key) != null
				&& localCache.getObject(key) != EXECUTION_PLACEHOLDER;
		}

		public void load() {
			@SuppressWarnings("unchecked")
			// we suppose we get back a List
				List<Object> list = (List<Object>) localCache.getObject(key);
			Object value = resultExtractor.extractObjectFromList(list, targetType);
			resultObject.setValue(property, value);
		}

	}

}
