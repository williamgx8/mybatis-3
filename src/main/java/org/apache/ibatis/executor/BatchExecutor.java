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

import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.keygen.Jdbc3KeyGenerator;
import org.apache.ibatis.executor.keygen.KeyGenerator;
import org.apache.ibatis.executor.keygen.NoKeyGenerator;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.transaction.Transaction;

/**
 * 带批处理的Executor，批处理不支持查询
 *
 * @author Jeff Butler
 */
public class BatchExecutor extends BaseExecutor {

	public static final int BATCH_UPDATE_RETURN_VALUE = Integer.MIN_VALUE + 1002;

	//暂存所有Statement集合
	private final List<Statement> statementList = new ArrayList<>();
	//批处理结果集合，每一个BatchResult和Statement一一对应
	private final List<BatchResult> batchResultList = new ArrayList<>();
	//当前sql
	private String currentSql;
	//执行所应对的Mapper.xml中某一个语句标签的对象
	private MappedStatement currentStatement;

	public BatchExecutor(Configuration configuration, Transaction transaction) {
		super(configuration, transaction);
	}

	@Override
	public int doUpdate(MappedStatement ms, Object parameterObject) throws SQLException {
		final Configuration configuration = ms.getConfiguration();
		//创建StatementHandler
		final StatementHandler handler = configuration
			.newStatementHandler(this, ms, parameterObject, RowBounds.DEFAULT, null, null);
		//BoundSql
		final BoundSql boundSql = handler.getBoundSql();
		//带？的sql
		final String sql = boundSql.getSql();
		final Statement stmt;
		//对于批处理来说currentSql只可能只有一个(不同的参数都是？)，同理currentStatement也是一个
		if (sql.equals(currentSql) && ms.equals(currentStatement)) {
			int last = statementList.size() - 1;
			//从Statement集合中取出
			stmt = statementList.get(last);
			//设置超时时间
			applyTransactionTimeout(stmt);
			//替换参数?
			handler.parameterize(stmt);//fix Issues 322
			//结果肯定也有了
			BatchResult batchResult = batchResultList.get(last);
			//将真实的参数放入BatchResult
			batchResult.addParameterObject(parameterObject);
		} else {
			//只有批处理执行的第一次才会到这
			Connection connection = getConnection(ms.getStatementLog());
			//创建Statement
			stmt = handler.prepare(connection, transaction.getTimeout());
			//替换参数？
			handler.parameterize(stmt);    //fix Issues 322
			//记录sql和MappedStatement
			currentSql = sql;
			currentStatement = ms;
			//放入集合
			statementList.add(stmt);
			batchResultList.add(new BatchResult(ms, sql, parameterObject));
		}
		// handler.parameterize(stmt);
		//交给各个数据库驱动进行批处理，此时并不是真的执行
		handler.batch(stmt);
		return BATCH_UPDATE_RETURN_VALUE;
	}

	/**
	 * 批处理的查询实际上就是通过查询，在查询之前需要先将暂存的所有批处理update执行掉
	 */
	@Override
	public <E> List<E> doQuery(MappedStatement ms, Object parameterObject, RowBounds rowBounds,
		ResultHandler resultHandler, BoundSql boundSql)
		throws SQLException {
		Statement stmt = null;
		try {
			//先将保存的所有批处理执行
			flushStatements();
			Configuration configuration = ms.getConfiguration();
			StatementHandler handler = configuration
				.newStatementHandler(wrapper, ms, parameterObject, rowBounds, resultHandler,
					boundSql);
			Connection connection = getConnection(ms.getStatementLog());
			//再为查询创建Statement
			stmt = handler.prepare(connection, transaction.getTimeout());
			//添加参数
			handler.parameterize(stmt);
			return handler.<E>query(stmt, resultHandler);
		} finally {
			closeStatement(stmt);
		}
	}

	@Override
	protected <E> Cursor<E> doQueryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds,
		BoundSql boundSql) throws SQLException {
		flushStatements();
		Configuration configuration = ms.getConfiguration();
		StatementHandler handler = configuration
			.newStatementHandler(wrapper, ms, parameter, rowBounds, null, boundSql);
		Connection connection = getConnection(ms.getStatementLog());
		Statement stmt = handler.prepare(connection, transaction.getTimeout());
		stmt.closeOnCompletion();
		handler.parameterize(stmt);
		return handler.<E>queryCursor(stmt);
	}

	/**
	 * 刷入批处理，一般批处理在commit、close等结束的时候统一执行处理
	 */
	@Override
	public List<BatchResult> doFlushStatements(boolean isRollback) throws SQLException {
		try {
			List<BatchResult> results = new ArrayList<>();
			if (isRollback) {
				return Collections.emptyList();
			}
			//遍历statement列表，逐个处理
			for (int i = 0, n = statementList.size(); i < n; i++) {
				Statement stmt = statementList.get(i);
				//每一个都设置超时时间
				applyTransactionTimeout(stmt);
				BatchResult batchResult = batchResultList.get(i);
				try {
					//执行并更新数量
					batchResult.setUpdateCounts(stmt.executeBatch());
					MappedStatement ms = batchResult.getMappedStatement();
					//所有参数
					List<Object> parameterObjects = batchResult.getParameterObjects();
					//根据主键生成策略生成主键
					KeyGenerator keyGenerator = ms.getKeyGenerator();
					if (Jdbc3KeyGenerator.class.equals(keyGenerator.getClass())) {
						Jdbc3KeyGenerator jdbc3KeyGenerator = (Jdbc3KeyGenerator) keyGenerator;
						jdbc3KeyGenerator.processBatch(ms, stmt, parameterObjects);
					} else if (!NoKeyGenerator.class.equals(keyGenerator.getClass())) { //issue #141
						for (Object parameter : parameterObjects) {
							keyGenerator.processAfter(this, ms, stmt, parameter);
						}
					}
					// Close statement to close cursor #1109
					//执行一个关闭一个
					closeStatement(stmt);
				} catch (BatchUpdateException e) {
					StringBuilder message = new StringBuilder();
					message.append(batchResult.getMappedStatement().getId())
						.append(" (batch index #")
						.append(i + 1)
						.append(")")
						.append(" failed.");
					if (i > 0) {
						message.append(" ")
							.append(i)
							.append(
								" prior sub executor(s) completed successfully, but will be rolled back.");
					}
					throw new BatchExecutorException(message.toString(), e, results, batchResult);
				}
				results.add(batchResult);
			}
			return results;
		} finally {
			for (Statement stmt : statementList) {
				closeStatement(stmt);
			}
			//清除各种sql和集合
			currentSql = null;
			statementList.clear();
			batchResultList.clear();
		}
	}

}
