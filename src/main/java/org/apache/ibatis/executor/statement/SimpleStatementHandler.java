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
package org.apache.ibatis.executor.statement;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.keygen.Jdbc3KeyGenerator;
import org.apache.ibatis.executor.keygen.KeyGenerator;
import org.apache.ibatis.executor.keygen.SelectKeyGenerator;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ResultSetType;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

/**
 * 不需要参数预处理的简单类型statement处理器
 *
 * @author Clinton Begin
 */
public class SimpleStatementHandler extends BaseStatementHandler {

	public SimpleStatementHandler(Executor executor, MappedStatement mappedStatement,
		Object parameter, RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql) {
		super(executor, mappedStatement, parameter, rowBounds, resultHandler, boundSql);
	}

	@Override
	public int update(Statement statement) throws SQLException {
		String sql = boundSql.getSql();
		Object parameterObject = boundSql.getParameterObject();
		KeyGenerator keyGenerator = mappedStatement.getKeyGenerator();
		int rows;
		if (keyGenerator instanceof Jdbc3KeyGenerator) {
			//执行更新
			statement.execute(sql, Statement.RETURN_GENERATED_KEYS);
			//返回影响的行数
			rows = statement.getUpdateCount();
			//主键生成器后处理
			keyGenerator.processAfter(executor, mappedStatement, statement, parameterObject);
		} else if (keyGenerator instanceof SelectKeyGenerator) {
			statement.execute(sql);
			rows = statement.getUpdateCount();
			keyGenerator.processAfter(executor, mappedStatement, statement, parameterObject);
		} else {
			statement.execute(sql);
			rows = statement.getUpdateCount();
		}
		return rows;
	}

	@Override
	public void batch(Statement statement) throws SQLException {
		String sql = boundSql.getSql();
		statement.addBatch(sql);
	}

	@Override
	public <E> List<E> query(Statement statement, ResultHandler resultHandler) throws SQLException {
		//获得sql
		String sql = boundSql.getSql();
		//因为是简单类型，直接执行
		statement.execute(sql);
		//处理结果集
		return resultSetHandler.<E>handleResultSets(statement);
	}

	@Override
	public <E> Cursor<E> queryCursor(Statement statement) throws SQLException {
		String sql = boundSql.getSql();
		statement.execute(sql);
		//处理带游标的结果集
		return resultSetHandler.<E>handleCursorResultSets(statement);
	}

	@Override
	protected Statement instantiateStatement(Connection connection) throws SQLException {
		//根据结果集类型创建不同的Statement
		if (mappedStatement.getResultSetType() == ResultSetType.DEFAULT) {
			return connection.createStatement();
		} else {
			return connection.createStatement(mappedStatement.getResultSetType().getValue(),
				ResultSet.CONCUR_READ_ONLY);
		}
	}

	@Override
	public void parameterize(Statement statement) throws SQLException {
		// N/A
		//没有参数占位符
	}

}
