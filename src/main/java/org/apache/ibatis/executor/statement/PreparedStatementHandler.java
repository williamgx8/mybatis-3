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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.keygen.Jdbc3KeyGenerator;
import org.apache.ibatis.executor.keygen.KeyGenerator;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ResultSetType;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

/**
 * 预处理语句处理器
 *
 * @author Clinton Begin
 */
public class PreparedStatementHandler extends BaseStatementHandler {

	public PreparedStatementHandler(Executor executor, MappedStatement mappedStatement,
		Object parameter, RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql) {
		super(executor, mappedStatement, parameter, rowBounds, resultHandler, boundSql);
	}

	@Override
	public int update(Statement statement) throws SQLException {
		PreparedStatement ps = (PreparedStatement) statement;
		//执行更新/插入
		ps.execute();
		//操作影响行数
		int rows = ps.getUpdateCount();
		Object parameterObject = boundSql.getParameterObject();
		KeyGenerator keyGenerator = mappedStatement.getKeyGenerator();
		//根据参数和主键生成器进行后续处理
		keyGenerator.processAfter(executor, mappedStatement, ps, parameterObject);
		return rows;
	}

	@Override
	public void batch(Statement statement) throws SQLException {
		PreparedStatement ps = (PreparedStatement) statement;
		ps.addBatch();
	}

	@Override
	public <E> List<E> query(Statement statement, ResultHandler resultHandler) throws SQLException {
		//此时参数已经完成设置，直接执行sql
		PreparedStatement ps = (PreparedStatement) statement;
		ps.execute();
		//结果集处理器处理结果
		return resultSetHandler.<E>handleResultSets(ps);
	}

	@Override
	public <E> Cursor<E> queryCursor(Statement statement) throws SQLException {
		PreparedStatement ps = (PreparedStatement) statement;
		ps.execute();
		return resultSetHandler.<E>handleCursorResultSets(ps);
	}

	@Override
	protected Statement instantiateStatement(Connection connection) throws SQLException {
		String sql = boundSql.getSql();
		if (mappedStatement.getKeyGenerator() instanceof Jdbc3KeyGenerator) {
			//如果存在主键生成器，需要根据主键生成器创建特定的Statement
			String[] keyColumnNames = mappedStatement.getKeyColumns();
			if (keyColumnNames == null) {
				return connection.prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS);
			} else {
				return connection.prepareStatement(sql, keyColumnNames);
			}
		} else if (mappedStatement.getResultSetType() == ResultSetType.DEFAULT) {
			//不存在主键生成器，且结果集处理类型默认值
			return connection.prepareStatement(sql);
		} else {
			//根据特定的结果集类型生成Statement
			return connection.prepareStatement(sql, mappedStatement.getResultSetType().getValue(),
				ResultSet.CONCUR_READ_ONLY);
		}
	}

	@Override
	public void parameterize(Statement statement) throws SQLException {
		//将参数? 替换成真正的参数值
		parameterHandler.setParameters((PreparedStatement) statement);
	}

}
