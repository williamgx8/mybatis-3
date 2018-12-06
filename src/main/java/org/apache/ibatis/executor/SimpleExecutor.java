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

import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.transaction.Transaction;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.List;

/**
 * @author Clinton Begin
 */
public class SimpleExecutor extends BaseExecutor {

	public SimpleExecutor(Configuration configuration, Transaction transaction) {
		super(configuration, transaction);
	}

	@Override
	public int doUpdate(MappedStatement ms, Object parameter) throws SQLException {
		Statement stmt = null;
		try {
			Configuration configuration = ms.getConfiguration();
			//创建StatementHandler
			StatementHandler handler = configuration
				.newStatementHandler(this, ms, parameter, RowBounds.DEFAULT, null, null);
			//创建并初始化Statement
			stmt = prepareStatement(handler, ms.getStatementLog());
			//执行更新操作
			return handler.update(stmt);
		} finally {
			//关闭statement
			closeStatement(stmt);
		}
	}

	@Override
	public <E> List<E> doQuery(MappedStatement ms, Object parameter, RowBounds rowBounds,
		ResultHandler resultHandler, BoundSql boundSql) throws SQLException {
		Statement stmt = null;
		try {
			Configuration configuration = ms.getConfiguration();
			//创建StatementHandler
			StatementHandler handler = configuration
				.newStatementHandler(wrapper, ms, parameter, rowBounds, resultHandler, boundSql);
			//创建并初始化Statement
			stmt = prepareStatement(handler, ms.getStatementLog());
			//执行查询操作
			return handler.<E>query(stmt, resultHandler);
		} finally {
			//关闭statement
			closeStatement(stmt);
		}
	}

	@Override
	protected <E> Cursor<E> doQueryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds,
		BoundSql boundSql) throws SQLException {
		Configuration configuration = ms.getConfiguration();
		//创建StatementHandler
		StatementHandler handler = configuration
			.newStatementHandler(wrapper, ms, parameter, rowBounds, null, boundSql);
		//创建并初始化Statement
		Statement stmt = prepareStatement(handler, ms.getStatementLog());
		//执行完成关闭
		stmt.closeOnCompletion();
		//查询
		return handler.<E>queryCursor(stmt);
	}

	@Override
	public List<BatchResult> doFlushStatements(boolean isRollback) throws SQLException {
		//SimpleExecutor不存在批处理，返回空集合
		return Collections.emptyList();
	}

	/**
	 * 预处理Statement，根据StatementHandler创建出一个初始化的Statement
	 */
	private Statement prepareStatement(StatementHandler handler, Log statementLog)
		throws SQLException {
		Statement stmt;
		Connection connection = getConnection(statementLog);
		//初始化生成不同数据库驱动对应的Statement
		stmt = handler.prepare(connection, transaction.getTimeout());
		//将请求参数放到statement对应位置，比如PreparedStatement的参数
		handler.parameterize(stmt);
		return stmt;
	}

}
