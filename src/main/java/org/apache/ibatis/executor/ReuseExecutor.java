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

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.transaction.Transaction;

/**
 * 可重用的Executor
 *
 * @author Clinton Begin
 */
public class ReuseExecutor extends BaseExecutor {

	//保存sql和对应statement的映射，key为不包含参数，只有？的sql
	private final Map<String, Statement> statementMap = new HashMap<>();

	public ReuseExecutor(Configuration configuration, Transaction transaction) {
		super(configuration, transaction);
	}

	@Override
	public int doUpdate(MappedStatement ms, Object parameter) throws SQLException {
		Configuration configuration = ms.getConfiguration();
		//创建StatementHandler
		StatementHandler handler = configuration
			.newStatementHandler(this, ms, parameter, RowBounds.DEFAULT, null, null);
		//获取/创建Statement
		Statement stmt = prepareStatement(handler, ms.getStatementLog());
		//更新
		return handler.update(stmt);
	}

	@Override
	public <E> List<E> doQuery(MappedStatement ms, Object parameter, RowBounds rowBounds,
		ResultHandler resultHandler, BoundSql boundSql) throws SQLException {
		Configuration configuration = ms.getConfiguration();
		//创建StatementHandler
		StatementHandler handler = configuration
			.newStatementHandler(wrapper, ms, parameter, rowBounds, resultHandler, boundSql);
		//获取/创建Statement
		Statement stmt = prepareStatement(handler, ms.getStatementLog());
		//查询
		return handler.<E>query(stmt, resultHandler);
	}

	@Override
	protected <E> Cursor<E> doQueryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds,
		BoundSql boundSql) throws SQLException {
		Configuration configuration = ms.getConfiguration();
		StatementHandler handler = configuration
			.newStatementHandler(wrapper, ms, parameter, rowBounds, null, boundSql);
		Statement stmt = prepareStatement(handler, ms.getStatementLog());
		return handler.<E>queryCursor(stmt);
	}

	/**
	 * 刷出所有statement
	 */
	@Override
	public List<BatchResult> doFlushStatements(boolean isRollback) throws SQLException {
		//从映射中取出Statement，挨个关闭
		for (Statement stmt : statementMap.values()) {
			closeStatement(stmt);
		}
		//将整个映射清空
		statementMap.clear();
		//返回空集合
		return Collections.emptyList();
	}

	/**
	 * 获得/创建Statement
	 */
	private Statement prepareStatement(StatementHandler handler, Log statementLog)
		throws SQLException {
		Statement stmt;
		//BoundSql
		BoundSql boundSql = handler.getBoundSql();
		//可能包含？的sql
		String sql = boundSql.getSql();
		if (hasStatementFor(sql)) {
			//已经存在sql对应的statement，直接取出来
			stmt = getStatement(sql);
			//设置超时
			applyTransactionTimeout(stmt);
		} else {
			//和sql对应的Statement不存在
			Connection connection = getConnection(statementLog);
			//新建一个Statement
			stmt = handler.prepare(connection, transaction.getTimeout());
			//放入可重用映射
			putStatement(sql, stmt);
		}
		//处理sql中的？
		handler.parameterize(stmt);
		return stmt;
	}

	/**
	 * 是否存在与sql对应的Statement
	 */
	private boolean hasStatementFor(String sql) {
		try {
			//映射中存在且没有关闭为true
			return statementMap.keySet().contains(sql) && !statementMap.get(sql).getConnection()
				.isClosed();
		} catch (SQLException e) {
			return false;
		}
	}

	private Statement getStatement(String s) {
		return statementMap.get(s);
	}

	private void putStatement(String sql, Statement stmt) {
		statementMap.put(sql, stmt);
	}

}
