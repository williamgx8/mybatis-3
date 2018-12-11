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
package org.apache.ibatis.executor.keygen;

import java.sql.Statement;
import java.util.List;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.ExecutorException;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.RowBounds;

/**
 * <selectKey/> 和 @SelectKey 对应的生成器，可以按照不同数据库驱动生成指定列的值，一般适用于Oracle和PostgreSQL
 *
 * @author Clinton Begin
 * @author Jeff Butler
 */
public class SelectKeyGenerator implements KeyGenerator {

	public static final String SELECT_KEY_SUFFIX = "!selectKey";
	private final boolean executeBefore;
	private final MappedStatement keyStatement;

	public SelectKeyGenerator(MappedStatement keyStatement, boolean executeBefore) {
		this.executeBefore = executeBefore;
		this.keyStatement = keyStatement;
	}

	@Override
	public void processBefore(Executor executor, MappedStatement ms, Statement stmt,
		Object parameter) {
		//根据executeBefore决定是前处理还是后处理
		if (executeBefore) {
			processGeneratedKeys(executor, ms, parameter);
		}
	}

	@Override
	public void processAfter(Executor executor, MappedStatement ms, Statement stmt,
		Object parameter) {
		//根据executeBefore决定是前处理还是后处理
		if (!executeBefore) {
			processGeneratedKeys(executor, ms, parameter);
		}
	}

	private void processGeneratedKeys(Executor executor, MappedStatement ms, Object parameter) {
		try {
			//参数、语句标签对象MappedStatement和keyGenerator配置缺一不可
			if (parameter != null && keyStatement != null
				&& keyStatement.getKeyProperties() != null) {
				//哪些列需要自动生成
				String[] keyProperties = keyStatement.getKeyProperties();
				final Configuration configuration = ms.getConfiguration();
				//参数元数据
				final MetaObject metaParam = configuration.newMetaObject(parameter);
				if (keyProperties != null) {
					// Do not close keyExecutor.
					// The transaction will be closed by parent executor.
					//创建SimpleExecutor简单执行器
					Executor keyExecutor = configuration
						.newExecutor(executor.getTransaction(), ExecutorType.SIMPLE);
					//获取生成的值
					List<Object> values = keyExecutor
						.query(keyStatement, parameter, RowBounds.DEFAULT,
							Executor.NO_RESULT_HANDLER);
					if (values.size() == 0) {
						throw new ExecutorException("SelectKey returned no data.");
					} else if (values.size() > 1) {
						throw new ExecutorException("SelectKey returned more than one value.");
					} else {
						//生成值元数据
						MetaObject metaResult = configuration.newMetaObject(values.get(0));
						if (keyProperties.length == 1) {
							//生成值和待生成字段一一对应
							//生成值元数据存在指定getter，通过getter得到值并设置
							if (metaResult.hasGetter(keyProperties[0])) {
								//设置生成值
								setValue(metaParam, keyProperties[0],
									metaResult.getValue(keyProperties[0]));
							} else {
								// no getter for the property - maybe just a single value object
								// so try that
								//没有getter，直接取出来设置
								setValue(metaParam, keyProperties[0], values.get(0));
							}
						} else {
							handleMultipleProperties(keyProperties, metaParam, metaResult);
						}
					}
				}
			}
		} catch (ExecutorException e) {
			throw e;
		} catch (Exception e) {
			throw new ExecutorException(
				"Error selecting key or setting result to parameter object. Cause: " + e, e);
		}
	}

	private void handleMultipleProperties(String[] keyProperties,
		MetaObject metaParam, MetaObject metaResult) {
		//配置的keyColumns
		String[] keyColumns = keyStatement.getKeyColumns();
		//没有配置keyColumn，遍历keyProperty，从生成值元数据中取出keyProperty对应的值，进行设置
		if (keyColumns == null || keyColumns.length == 0) {
			// no key columns specified, just use the property names
			for (String keyProperty : keyProperties) {
				setValue(metaParam, keyProperty, metaResult.getValue(keyProperty));
			}
		} else {
			//存在keyColumns配置，和keyProperty的个数必须一致
			if (keyColumns.length != keyProperties.length) {
				throw new ExecutorException(
					"If SelectKey has key columns, the number must match the number of key properties.");
			}
			//以keyColumn为准，从生成值元数据中取出，进行设置
			for (int i = 0; i < keyProperties.length; i++) {
				setValue(metaParam, keyProperties[i], metaResult.getValue(keyColumns[i]));
			}
		}
	}

	private void setValue(MetaObject metaParam, String property, Object value) {
		//property存在对应setter
		if (metaParam.hasSetter(property)) {
			//设置值
			metaParam.setValue(property, value);
		} else {
			throw new ExecutorException(
				"No setter found for the keyProperty '" + property + "' in " + metaParam
					.getOriginalObject().getClass().getName() + ".");
		}
	}
}
