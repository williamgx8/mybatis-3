/**
 * Copyright 2009-2016 the original author or authors.
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
package org.apache.ibatis.executor.statement;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.ExecutorException;
import org.apache.ibatis.executor.keygen.KeyGenerator;
import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.executor.resultset.ResultSetHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.type.TypeHandlerRegistry;

/**
 * StatementHandler基类
 *
 * @author Clinton Begin
 */
public abstract class BaseStatementHandler implements StatementHandler {

	protected final Configuration configuration;
	protected final ObjectFactory objectFactory;
	protected final TypeHandlerRegistry typeHandlerRegistry;
	protected final ResultSetHandler resultSetHandler;
	protected final ParameterHandler parameterHandler;

	protected final Executor executor;
	protected final MappedStatement mappedStatement;
	protected final RowBounds rowBounds;

	protected BoundSql boundSql;

	protected BaseStatementHandler(Executor executor, MappedStatement mappedStatement,
		Object parameterObject, RowBounds rowBounds, ResultHandler resultHandler,
		BoundSql boundSql) {
		this.configuration = mappedStatement.getConfiguration();
		this.executor = executor;
		this.mappedStatement = mappedStatement;
		this.rowBounds = rowBounds;

		this.typeHandlerRegistry = configuration.getTypeHandlerRegistry();
		this.objectFactory = configuration.getObjectFactory();

		//在update、insert等操作时可能存在主键通过某种策略生成的情况，那么sql语句中主键的内容
		//需要首先通过KeyGenerator创建，并替换到sql中特定的位置
		if (boundSql == null) { // issue #435, get the key before calculating the statement
			//产生主键
			generateKeys(parameterObject);
			//获得BoundSql，此时sql中的主键已经变成上面计算出的结果
			boundSql = mappedStatement.getBoundSql(parameterObject);
		}

		this.boundSql = boundSql;

		//创建参数处理器
		this.parameterHandler = configuration
			.newParameterHandler(mappedStatement, parameterObject, boundSql);
		//结果及处理器
		this.resultSetHandler = configuration
			.newResultSetHandler(executor, mappedStatement, rowBounds, parameterHandler,
				resultHandler, boundSql);
	}

	@Override
	public BoundSql getBoundSql() {
		return boundSql;
	}

	@Override
	public ParameterHandler getParameterHandler() {
		return parameterHandler;
	}

	/**
	 * 新建Statement并设置一些基本属性
	 */
	@Override
	public Statement prepare(Connection connection, Integer transactionTimeout)
		throws SQLException {
		ErrorContext.instance().sql(boundSql.getSql());
		Statement statement = null;
		try {
			//创建Statement
			statement = instantiateStatement(connection);
			//设置事务超时时间
			setStatementTimeout(statement, transactionTimeout);
			//设置操作数据集大小
			setFetchSize(statement);
			return statement;
		} catch (SQLException e) {
			//关闭Statement
			closeStatement(statement);
			throw e;
		} catch (Exception e) {
			closeStatement(statement);
			throw new ExecutorException("Error preparing statement.  Cause: " + e, e);
		}
	}

	protected abstract Statement instantiateStatement(Connection connection) throws SQLException;

	protected void setStatementTimeout(Statement stmt, Integer transactionTimeout)
		throws SQLException {
		Integer queryTimeout = null;
		if (mappedStatement.getTimeout() != null) {
			queryTimeout = mappedStatement.getTimeout();
		} else if (configuration.getDefaultStatementTimeout() != null) {
			queryTimeout = configuration.getDefaultStatementTimeout();
		}
		if (queryTimeout != null) {
			stmt.setQueryTimeout(queryTimeout);
		}
		StatementUtil.applyTransactionTimeout(stmt, queryTimeout, transactionTimeout);
	}

	/**
	 * 设置操作数据集大小
	 */
	protected void setFetchSize(Statement stmt) throws SQLException {
		//当前的Mapper.xml语句标签中设置了Statement的数据及大小
		Integer fetchSize = mappedStatement.getFetchSize();
		if (fetchSize != null) {
			//按照设置来
			stmt.setFetchSize(fetchSize);
			return;
		}
		//没有设置按照Config.xml中的默认总设置来
		Integer defaultFetchSize = configuration.getDefaultFetchSize();
		if (defaultFetchSize != null) {
			stmt.setFetchSize(defaultFetchSize);
		}
	}

	protected void closeStatement(Statement statement) {
		try {
			if (statement != null) {
				statement.close();
			}
		} catch (SQLException e) {
			//ignore
		}
	}

	protected void generateKeys(Object parameter) {
		//主键生成器
		KeyGenerator keyGenerator = mappedStatement.getKeyGenerator();
		//将当前线程相关的ErrorContext暂存起来，创建一个新的，放入ThreadLocal中
		ErrorContext.instance().store();
		//前置处理，只有SelectKeyGenerator才存在真正的处理
		keyGenerator.processBefore(executor, mappedStatement, null, parameter);
		//如果前置处理没有出错，就把原先保存的当前线程ErrorContext换回去
		ErrorContext.instance().recall();
	}

}
