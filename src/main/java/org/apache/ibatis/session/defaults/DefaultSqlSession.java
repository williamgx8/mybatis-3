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
package org.apache.ibatis.session.defaults;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.binding.BindingException;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.exceptions.ExceptionFactory;
import org.apache.ibatis.exceptions.TooManyResultsException;
import org.apache.ibatis.executor.BatchResult;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.result.DefaultMapResultHandler;
import org.apache.ibatis.executor.result.DefaultResultContext;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.session.SqlSession;

/**
 * The default implementation for {@link SqlSession}.
 * Note that this class is not Thread-Safe.
 *
 * @author Clinton Begin
 */
public class DefaultSqlSession implements SqlSession {

	//mybatis配置对象
	private final Configuration configuration;
	//执行器对象
	private final Executor executor;

	//是否自动提交
	private final boolean autoCommit;
	//是否发生数据变更
	private boolean dirty;
	private List<Cursor<?>> cursorList;

	public DefaultSqlSession(Configuration configuration, Executor executor, boolean autoCommit) {
		this.configuration = configuration;
		this.executor = executor;
		this.dirty = false;
		this.autoCommit = autoCommit;
	}

	public DefaultSqlSession(Configuration configuration, Executor executor) {
		this(configuration, executor, false);
	}

	@Override
	public <T> T selectOne(String statement) {
		return this.<T>selectOne(statement, null);
	}

	@Override
	public <T> T selectOne(String statement, Object parameter) {
		// Popular vote was to return null on 0 results and throw exception on too many.
		//依然调用selectList
		List<T> list = this.<T>selectList(statement, parameter);
		if (list.size() == 1) {
			//只有一个返回第一个
			return list.get(0);
		} else if (list.size() > 1) {
			//大于一个报错
			throw new TooManyResultsException(
				"Expected one result (or null) to be returned by selectOne(), but found: " + list
					.size());
		} else {
			return null;
		}
	}

	@Override
	public <K, V> Map<K, V> selectMap(String statement, String mapKey) {
		return this.selectMap(statement, null, mapKey, RowBounds.DEFAULT);
	}

	@Override
	public <K, V> Map<K, V> selectMap(String statement, Object parameter, String mapKey) {
		return this.selectMap(statement, parameter, mapKey, RowBounds.DEFAULT);
	}

	@Override
	public <K, V> Map<K, V> selectMap(String statement, Object parameter, String mapKey,
		RowBounds rowBounds) {
		//selectList查询
		final List<? extends V> list = selectList(statement, parameter, rowBounds);
		//Map结果集处理器
		final DefaultMapResultHandler<K, V> mapResultHandler = new DefaultMapResultHandler<>(mapKey,
			configuration.getObjectFactory(), configuration.getObjectWrapperFactory(),
			configuration.getReflectorFactory());
		final DefaultResultContext<V> context = new DefaultResultContext<>();
		//遍历每一个结果
		for (V o : list) {
			//放入结果上下文，并将总结果数量加一
			context.nextResultObject(o);
			//交给结果处理器处理结果
			mapResultHandler.handleResult(context);
		}
		//返回转换成Map的值
		return mapResultHandler.getMappedResults();
	}

	@Override
	public <T> Cursor<T> selectCursor(String statement) {
		return selectCursor(statement, null);
	}

	@Override
	public <T> Cursor<T> selectCursor(String statement, Object parameter) {
		return selectCursor(statement, parameter, RowBounds.DEFAULT);
	}

	@Override
	public <T> Cursor<T> selectCursor(String statement, Object parameter, RowBounds rowBounds) {
		try {
			//获取statement对应的Mapper
			MappedStatement ms = configuration.getMappedStatement(statement);
			//查询获得游标
			Cursor<T> cursor = executor.queryCursor(ms, wrapCollection(parameter), rowBounds);
			//添加到游标集合中
			registerCursor(cursor);
			return cursor;
		} catch (Exception e) {
			throw ExceptionFactory.wrapException("Error querying database.  Cause: " + e, e);
		} finally {
			ErrorContext.instance().reset();
		}
	}

	@Override
	public <E> List<E> selectList(String statement) {
		return this.selectList(statement, null);
	}

	@Override
	public <E> List<E> selectList(String statement, Object parameter) {
		return this.selectList(statement, parameter, RowBounds.DEFAULT);
	}

	@Override
	public <E> List<E> selectList(String statement, Object parameter, RowBounds rowBounds) {
		try {
			//获取statement对应的MappedStatement，解析是在解析mybatis的config文件的最后一步
			MappedStatement ms = configuration.getMappedStatement(statement);
			//执行查询
			return executor
				.query(ms, wrapCollection(parameter), rowBounds, Executor.NO_RESULT_HANDLER);
		} catch (Exception e) {
			throw ExceptionFactory.wrapException("Error querying database.  Cause: " + e, e);
		} finally {
			ErrorContext.instance().reset();
		}
	}

	@Override
	public void select(String statement, Object parameter, ResultHandler handler) {
		select(statement, parameter, RowBounds.DEFAULT, handler);
	}

	@Override
	public void select(String statement, ResultHandler handler) {
		select(statement, null, RowBounds.DEFAULT, handler);
	}

	@Override
	public void select(String statement, Object parameter, RowBounds rowBounds,
		ResultHandler handler) {
		try {
			MappedStatement ms = configuration.getMappedStatement(statement);
			//与selectOne和selectList等查询不同的是，这里的ResultHandler是外部传入的
			executor.query(ms, wrapCollection(parameter), rowBounds, handler);
		} catch (Exception e) {
			throw ExceptionFactory.wrapException("Error querying database.  Cause: " + e, e);
		} finally {
			ErrorContext.instance().reset();
		}
	}

	@Override
	public int insert(String statement) {
		return insert(statement, null);
	}

	@Override
	public int insert(String statement, Object parameter) {
		return update(statement, parameter);
	}

	@Override
	public int update(String statement) {
		return update(statement, null);
	}

	@Override
	public int update(String statement, Object parameter) {
		try {
			//更新会对其他session操作产生脏数据，我理解在InnoDB引擎下mysql的隔离级别为repeatable read，只会出现幻读
			dirty = true;
			//statement对应的Mapper
			MappedStatement ms = configuration.getMappedStatement(statement);
			//调用执行器的更新
			return executor.update(ms, wrapCollection(parameter));
		} catch (Exception e) {
			throw ExceptionFactory.wrapException("Error updating database.  Cause: " + e, e);
		} finally {
			ErrorContext.instance().reset();
		}
	}

	@Override
	public int delete(String statement) {
		return update(statement, null);
	}

	@Override
	public int delete(String statement, Object parameter) {
		return update(statement, parameter);
	}

	@Override
	public void commit() {
		commit(false);
	}

	@Override
	public void commit(boolean force) {
		try {
			//根据多参数判断是否需要提交，是否提交是由执行器控制，但最终事务提交的动作是由不同数据库的Connection实现
			executor.commit(isCommitOrRollbackRequired(force));
			//无脏数据
			dirty = false;
		} catch (Exception e) {
			throw ExceptionFactory.wrapException("Error committing transaction.  Cause: " + e, e);
		} finally {
			ErrorContext.instance().reset();
		}
	}

	@Override
	public void rollback() {
		rollback(false);
	}

	@Override
	public void rollback(boolean force) {
		try {
			executor.rollback(isCommitOrRollbackRequired(force));
			dirty = false;
		} catch (Exception e) {
			throw ExceptionFactory.wrapException("Error rolling back transaction.  Cause: " + e, e);
		} finally {
			ErrorContext.instance().reset();
		}
	}

	@Override
	public List<BatchResult> flushStatements() {
		try {
			return executor.flushStatements();
		} catch (Exception e) {
			throw ExceptionFactory.wrapException("Error flushing statements.  Cause: " + e, e);
		} finally {
			ErrorContext.instance().reset();
		}
	}

	@Override
	public void close() {
		try {
			//根据是否自动提交和dirty判断是否关闭
			executor.close(isCommitOrRollbackRequired(false));
			//关闭游标
			closeCursors();
			//无脏数据
			dirty = false;
		} finally {
			ErrorContext.instance().reset();
		}
	}

	private void closeCursors() {
		if (cursorList != null && cursorList.size() != 0) {
			for (Cursor<?> cursor : cursorList) {
				try {
					cursor.close();
				} catch (IOException e) {
					throw ExceptionFactory.wrapException("Error closing cursor.  Cause: " + e, e);
				}
			}
			cursorList.clear();
		}
	}

	@Override
	public Configuration getConfiguration() {
		return configuration;
	}

	@Override
	public <T> T getMapper(Class<T> type) {
		return configuration.<T>getMapper(type, this);
	}

	@Override
	public Connection getConnection() {
		try {
			return executor.getTransaction().getConnection();
		} catch (SQLException e) {
			throw ExceptionFactory.wrapException("Error getting a new connection.  Cause: " + e, e);
		}
	}

	@Override
	public void clearCache() {
		executor.clearLocalCache();
	}

	private <T> void registerCursor(Cursor<T> cursor) {
		if (cursorList == null) {
			cursorList = new ArrayList<>();
		}
		cursorList.add(cursor);
	}

	private boolean isCommitOrRollbackRequired(boolean force) {
		//只要开启强制，不管怎么样都会提交或回滚
		/**
		 * 由于一个session中可以存在多次事务，如果某次事务update了数据但
		 * 还没有提交/回滚事务，肯定存在脏数据dirty，如果设置了autoCommit
		 * 自动提交，每次语句执行后都会自动处理事务，不需要这里调用commit，
		 * 所以要将自动提交的情况去除
		 */

		return (!autoCommit && dirty) || force;
	}

	/**
	 * 对于数组、集合类型属性的包装，普通类型的属性直接返回
	 */
	private Object wrapCollection(final Object object) {
		//如果参数为Collection
		if (object instanceof Collection) {
			StrictMap<Object> map = new StrictMap<>();
			//创建一个StrictMap，将参数放入key为collection的映射中
			map.put("collection", object);
			if (object instanceof List) {
				//如果参数是List，再放一次key为list的映射
				map.put("list", object);
			}
			//返回StrictMap
			return map;
		} else if (object != null && object.getClass().isArray()) {
			//参数为数组，同样创建StrictMap
			StrictMap<Object> map = new StrictMap<>();
			//参数放入key为array的映射
			map.put("array", object);
			return map;
		}
		//其他普通属性直接返回
		return object;
	}

	public static class StrictMap<V> extends HashMap<String, V> {

		private static final long serialVersionUID = -5741767162221585340L;

		@Override
		public V get(Object key) {
			if (!super.containsKey(key)) {
				throw new BindingException(
					"Parameter '" + key + "' not found. Available parameters are " + this.keySet());
			}
			return super.get(key);
		}

	}

}
