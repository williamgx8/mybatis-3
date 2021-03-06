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

import java.util.ArrayList;
import java.util.List;

import org.apache.ibatis.mapping.MappedStatement;

/**
 * 每一条批处理结果的封装对象
 *
 * @author Jeff Butler
 */
public class BatchResult {

	//批处理对应的一条语句标签/注解
	private final MappedStatement mappedStatement;
	private final String sql;
	//参数集合
	private final List<Object> parameterObjects;
	//更新的数量
	private int[] updateCounts;

	public BatchResult(MappedStatement mappedStatement, String sql) {
		super();
		this.mappedStatement = mappedStatement;
		this.sql = sql;
		this.parameterObjects = new ArrayList<>();
	}

	public BatchResult(MappedStatement mappedStatement, String sql, Object parameterObject) {
		this(mappedStatement, sql);
		//添加参数入参数集合
		addParameterObject(parameterObject);
	}

	public MappedStatement getMappedStatement() {
		return mappedStatement;
	}

	public String getSql() {
		return sql;
	}

	@Deprecated
	public Object getParameterObject() {
		return parameterObjects.get(0);
	}

	public List<Object> getParameterObjects() {
		return parameterObjects;
	}

	public int[] getUpdateCounts() {
		return updateCounts;
	}

	/**
	 * 设置执行数量
	 */
	public void setUpdateCounts(int[] updateCounts) {
		this.updateCounts = updateCounts;
	}

	/**
	 * 添加参数入参数集合
	 */
	public void addParameterObject(Object parameterObject) {
		this.parameterObjects.add(parameterObject);
	}

}
