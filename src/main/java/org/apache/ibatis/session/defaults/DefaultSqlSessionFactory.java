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
package org.apache.ibatis.session.defaults;

import java.sql.Connection;
import java.sql.SQLException;

import org.apache.ibatis.exceptions.ExceptionFactory;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.TransactionIsolationLevel;
import org.apache.ibatis.transaction.Transaction;
import org.apache.ibatis.transaction.TransactionFactory;
import org.apache.ibatis.transaction.managed.ManagedTransactionFactory;

/**
 * @author Clinton Begin
 */
public class DefaultSqlSessionFactory implements SqlSessionFactory {

	private final Configuration configuration;

	public DefaultSqlSessionFactory(Configuration configuration) {
		this.configuration = configuration;
	}

	@Override
	public SqlSession openSession() {
		return openSessionFromDataSource(configuration.getDefaultExecutorType(), null, false);
	}

	@Override
	public SqlSession openSession(boolean autoCommit) {
		return openSessionFromDataSource(configuration.getDefaultExecutorType(), null, autoCommit);
	}

	@Override
	public SqlSession openSession(ExecutorType execType) {
		return openSessionFromDataSource(execType, null, false);
	}

	@Override
	public SqlSession openSession(TransactionIsolationLevel level) {
		return openSessionFromDataSource(configuration.getDefaultExecutorType(), level, false);
	}

	@Override
	public SqlSession openSession(ExecutorType execType, TransactionIsolationLevel level) {
		return openSessionFromDataSource(execType, level, false);
	}

	@Override
	public SqlSession openSession(ExecutorType execType, boolean autoCommit) {
		return openSessionFromDataSource(execType, null, autoCommit);
	}

	@Override
	public SqlSession openSession(Connection connection) {
		return openSessionFromConnection(configuration.getDefaultExecutorType(), connection);
	}

	@Override
	public SqlSession openSession(ExecutorType execType, Connection connection) {
		return openSessionFromConnection(execType, connection);
	}

	@Override
	public Configuration getConfiguration() {
		return configuration;
	}

	/**
	 * 根据执行器类型、事务隔离级别和是否自动提交创建SqlSession
	 *
	 * @param execType 执行器类型
	 * @param level 事务隔离级别
	 * @param autoCommit 是否自动提交
	 */
	private SqlSession openSessionFromDataSource(ExecutorType execType,
		TransactionIsolationLevel level, boolean autoCommit) {
		Transaction tx = null;
		try {
			//当前环境
			final Environment environment = configuration.getEnvironment();
			//从当前环境中获得事务工厂
			final TransactionFactory transactionFactory = getTransactionFactoryFromEnvironment(
				environment);
			//创建事务
			tx = transactionFactory.newTransaction(environment.getDataSource(), level, autoCommit);
			//创建执行器
			final Executor executor = configuration.newExecutor(tx, execType);
			//创建默认SqlSession
			return new DefaultSqlSession(configuration, executor, autoCommit);
		} catch (Exception e) {
			//异常需要关闭事务
			closeTransaction(tx); // may have fetched a connection so lets call close()
			throw ExceptionFactory.wrapException("Error opening session.  Cause: " + e, e);
		} finally {
			//重置异常上下文
			ErrorContext.instance().reset();
		}
	}

	private SqlSession openSessionFromConnection(ExecutorType execType, Connection connection) {
		try {
			boolean autoCommit;
			try {
				//从当前连接获得是否自动提交属性
				autoCommit = connection.getAutoCommit();
			} catch (SQLException e) {
				// Failover to true, as most poor drivers
				// or databases won't support transactions
				autoCommit = true;
			}
			//获得当前环境
			final Environment environment = configuration.getEnvironment();
			//根据当前环境创建事务工厂
			final TransactionFactory transactionFactory = getTransactionFactoryFromEnvironment(
				environment);
			//创建事务
			final Transaction tx = transactionFactory.newTransaction(connection);
			//创建执行器
			final Executor executor = configuration.newExecutor(tx, execType);
			//创建默认SqlSession
			return new DefaultSqlSession(configuration, executor, autoCommit);
		} catch (Exception e) {
			throw ExceptionFactory.wrapException("Error opening session.  Cause: " + e, e);
		} finally {
			//重置异常上下文
			ErrorContext.instance().reset();
		}
	}

	/**
	 * 根据环境得到事务工厂
	 */
	private TransactionFactory getTransactionFactoryFromEnvironment(Environment environment) {
		//环境对象为null，或者环境中不存在事务工厂，默认返回ManagedTransactionFactory
		if (environment == null || environment.getTransactionFactory() == null) {
			return new ManagedTransactionFactory();
		}
		//否则从环境中直接获取
		return environment.getTransactionFactory();
	}

	private void closeTransaction(Transaction tx) {
		if (tx != null) {
			try {
				tx.close();
			} catch (SQLException ignore) {
				// Intentionally ignore. Prefer previous error.
			}
		}
	}

}
