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
package org.apache.ibatis.executor;

import java.sql.SQLException;
import java.util.List;

import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.transaction.Transaction;

/**
 * @author Clinton Begin
 */
public interface Executor {

	ResultHandler NO_RESULT_HANDLER = null;

	/**
	 * 更新或插入操作
	 */
	int update(MappedStatement ms, Object parameter) throws SQLException;

	/**
	 * 查询
	 */
	<E> List<E> query(MappedStatement ms, Object parameter, RowBounds rowBounds,
		ResultHandler resultHandler, CacheKey cacheKey, BoundSql boundSql) throws SQLException;

	/**
	 * 查询
	 */
	<E> List<E> query(MappedStatement ms, Object parameter, RowBounds rowBounds,
		ResultHandler resultHandler) throws SQLException;

	/**
	 * 返回值为cursor的查询
	 */
	<E> Cursor<E> queryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds)
		throws SQLException;

	List<BatchResult> flushStatements() throws SQLException;

	/**
	 * 提交
	 */
	void commit(boolean required) throws SQLException;

	/**
	 * 回滚
	 */
	void rollback(boolean required) throws SQLException;

	/**
	 * 创建CacheKey
	 */
	CacheKey createCacheKey(MappedStatement ms, Object parameterObject, RowBounds rowBounds,
		BoundSql boundSql);

	/**
	 * key是否缓存
	 */
	boolean isCached(MappedStatement ms, CacheKey key);

	/**
	 * 清除本地缓存
	 */
	void clearLocalCache();

	/**
	 * 延迟加载
	 */
	void deferLoad(MappedStatement ms, MetaObject resultObject, String property, CacheKey key,
		Class<?> targetType);

	/**
	 * 获得事务对象
	 */
	Transaction getTransaction();

	/**
	 * 关闭事务
	 */
	void close(boolean forceRollback);

	/**
	 * 事务是否关闭
	 */
	boolean isClosed();

	/**
	 * 设置包装Executor对象
	 */
	void setExecutorWrapper(Executor executor);

}
