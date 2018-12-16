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
package org.apache.ibatis.executor.loader;

import java.sql.SQLException;
import java.util.List;

import javax.sql.DataSource;

import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.ExecutorException;
import org.apache.ibatis.executor.ResultExtractor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.transaction.Transaction;
import org.apache.ibatis.transaction.TransactionFactory;

/**
 * @author Clinton Begin
 */
public class ResultLoader {

	protected final Configuration configuration;
	protected final Executor executor;
	protected final MappedStatement mappedStatement;
	protected final Object parameterObject;
	protected final Class<?> targetType;
	protected final ObjectFactory objectFactory;
	protected final CacheKey cacheKey;
	//包含完整sql的BoundSql对象
	protected final BoundSql boundSql;
	//结果提取器
	protected final ResultExtractor resultExtractor;
	//当前线程id
	protected final long creatorThreadId;

	protected boolean loaded;
	//获取到的结果
	protected Object resultObject;

	public ResultLoader(Configuration config, Executor executor, MappedStatement mappedStatement,
		Object parameterObject, Class<?> targetType, CacheKey cacheKey, BoundSql boundSql) {
		this.configuration = config;
		this.executor = executor;
		this.mappedStatement = mappedStatement;
		this.parameterObject = parameterObject;
		this.targetType = targetType;
		this.objectFactory = configuration.getObjectFactory();
		this.cacheKey = cacheKey;
		this.boundSql = boundSql;
		this.resultExtractor = new ResultExtractor(configuration, objectFactory);
		this.creatorThreadId = Thread.currentThread().getId();
	}

	/**
	 * 按照先缓存后db的顺序加载结果
	 */
	public Object loadResult() throws SQLException {
		//加载结果
		List<Object> list = selectList();
		//提取结果
		resultObject = resultExtractor.extractObjectFromList(list, targetType);
		//返回结果
		return resultObject;
	}

	private <E> List<E> selectList() throws SQLException {
		Executor localExecutor = executor;
		//当前线程和创建ResultLoader的线程不是同一个，或者执行器已经关闭了
		if (Thread.currentThread().getId() != this.creatorThreadId || localExecutor.isClosed()) {
			//创建一个新的执行器
			localExecutor = newExecutor();
		}
		try {
			//查询
			return localExecutor.<E>query(mappedStatement, parameterObject, RowBounds.DEFAULT,
				Executor.NO_RESULT_HANDLER, cacheKey, boundSql);
		} finally {
			//关闭执行器
			if (localExecutor != executor) {
				localExecutor.close(false);
			}
		}
	}

	/**
	 * 创建新执行器
	 */
	private Executor newExecutor() {
		final Environment environment = configuration.getEnvironment();
		if (environment == null) {
			throw new ExecutorException(
				"ResultLoader could not load lazily.  Environment was not configured.");
		}
		//根据当前环境得到数据源
		final DataSource ds = environment.getDataSource();
		if (ds == null) {
			throw new ExecutorException(
				"ResultLoader could not load lazily.  DataSource was not configured.");
		}
		//根据当前环境得到事务工厂
		final TransactionFactory transactionFactory = environment.getTransactionFactory();
		//创建新事物
		final Transaction tx = transactionFactory.newTransaction(ds, null, false);
		return configuration.newExecutor(tx, ExecutorType.SIMPLE);
	}

	public boolean wasNull() {
		return resultObject == null;
	}

}
