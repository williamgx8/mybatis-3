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
package org.apache.ibatis.executor.resultset;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.ObjectTypeHandler;
import org.apache.ibatis.type.TypeHandler;
import org.apache.ibatis.type.TypeHandlerRegistry;
import org.apache.ibatis.type.UnknownTypeHandler;

/**
 * @author Iwao AVE!
 */
public class ResultSetWrapper {

	private final ResultSet resultSet;
	private final TypeHandlerRegistry typeHandlerRegistry;
	private final List<String> columnNames = new ArrayList<>();
	private final List<String> classNames = new ArrayList<>();
	private final List<JdbcType> jdbcTypes = new ArrayList<>();
	private final Map<String, Map<Class<?>, TypeHandler<?>>> typeHandlerMap = new HashMap<>();
	private final Map<String, List<String>> mappedColumnNamesMap = new HashMap<>();
	private final Map<String, List<String>> unMappedColumnNamesMap = new HashMap<>();

	public ResultSetWrapper(ResultSet rs, Configuration configuration) throws SQLException {
		super();
		this.typeHandlerRegistry = configuration.getTypeHandlerRegistry();
		this.resultSet = rs;
		//从结果集中得到结果集元数据
		final ResultSetMetaData metaData = rs.getMetaData();
		//列的个数
		final int columnCount = metaData.getColumnCount();
		//遍历每一列，获得列名、jdbcType和java类名
		for (int i = 1; i <= columnCount; i++) {
			columnNames.add(configuration.isUseColumnLabel() ? metaData.getColumnLabel(i)
				: metaData.getColumnName(i));
			jdbcTypes.add(JdbcType.forCode(metaData.getColumnType(i)));
			classNames.add(metaData.getColumnClassName(i));
		}
	}

	public ResultSet getResultSet() {
		return resultSet;
	}

	public List<String> getColumnNames() {
		return this.columnNames;
	}

	public List<String> getClassNames() {
		return Collections.unmodifiableList(classNames);
	}

	public List<JdbcType> getJdbcTypes() {
		return jdbcTypes;
	}

	/**
	 * 根据列名获得JdbcType
	 */
	public JdbcType getJdbcType(String columnName) {
		//遍历所有列，名称匹配的下标在jdbcTypes中对应的值就是查找列的jdbcType
		for (int i = 0; i < columnNames.size(); i++) {
			if (columnNames.get(i).equalsIgnoreCase(columnName)) {
				return jdbcTypes.get(i);
			}
		}
		return null;
	}

	/**
	 * 根据列名和属性Class类型得到对应类型处理器
	 * Gets the type handler to use when reading the result set.
	 * Tries to get from the TypeHandlerRegistry by searching for the property type.
	 * If not found it gets the column JDBC type and tries to get a handler for it.
	 */
	public TypeHandler<?> getTypeHandler(Class<?> propertyType, String columnName) {
		TypeHandler<?> handler = null;
		//根据列名获得所有的类型转换器，一个列可能有多个类型处理器，比如某列是int类型，那么可能存在int -> Integer和Integer -> Integer两种
		Map<Class<?>, TypeHandler<?>> columnHandlers = typeHandlerMap.get(columnName);
		if (columnHandlers == null) {
			//不存在，放一个空的TypeHandler，后面再填充
			columnHandlers = new HashMap<>();
			typeHandlerMap.put(columnName, columnHandlers);
		} else {
			//根据参数具体的类型
			handler = columnHandlers.get(propertyType);
		}
		//没有类型处理器
		if (handler == null) {
			//获得列对应JdbcType
			JdbcType jdbcType = getJdbcType(columnName);
			//参数类型以及参数对应列jdbcType再尝试获取类型处理器
			handler = typeHandlerRegistry.getTypeHandler(propertyType, jdbcType);
			// Replicate logic of UnknownTypeHandler#resolveTypeHandler
			// See issue #59 comment 10
			//还是没有
			if (handler == null || handler instanceof UnknownTypeHandler) {
				//列名在列名集合中的位置
				final int index = columnNames.indexOf(columnName);
				//根据对应的class name获得列的java类型
				final Class<?> javaType = resolveClass(classNames.get(index));
				if (javaType != null && jdbcType != null) {
					//根据javaType和jdbcType获取类型处理器
					handler = typeHandlerRegistry.getTypeHandler(javaType, jdbcType);
				} else if (javaType != null) {
					//只根据javaType获取
					handler = typeHandlerRegistry.getTypeHandler(javaType);
				} else if (jdbcType != null) {
					//只根据jdbcType获取
					handler = typeHandlerRegistry.getTypeHandler(jdbcType);
				}
			}
			if (handler == null || handler instanceof UnknownTypeHandler) {
				//依然没有返回Object类型处理器
				handler = new ObjectTypeHandler();
			}
			//放入typeHandlerMap
			columnHandlers.put(propertyType, handler);
		}
		return handler;
	}

	private Class<?> resolveClass(String className) {
		try {
			// #699 className could be null
			if (className != null) {
				return Resources.classForName(className);
			}
		} catch (ClassNotFoundException e) {
			// ignore
		}
		return null;
	}

	/**
	 * columnPrefix不为空对应如下情况：
	 * <resultMap type="org.apache.ibatis.submitted.ancestor_ref.User"
	 * id="userMapCollection">
	 * <id property="id" column="id" />
	 * <result property="name" column="name" />
	 * <collection property="friends" resultMap="userMapCollection"
	 * columnPrefix="friend_" />
	 * </resultMap>
	 * 结果集中的某个属性又是结果集本身，比如例子中的friends，为了区分resultMap本身和属性对应的同一个
	 * resultMap，在属性对应的resultMap中加一个前缀
	 */
	private void loadMappedAndUnmappedColumnNames(ResultMap resultMap, String columnPrefix)
		throws SQLException {
		List<String> mappedColumnNames = new ArrayList<>();
		List<String> unmappedColumnNames = new ArrayList<>();
		//如果存在前缀将前缀大写
		final String upperColumnPrefix =
			columnPrefix == null ? null : columnPrefix.toUpperCase(Locale.ENGLISH);
		//将前缀加到<resultMap/>中的普通属性前，可能存在前缀也可能不存在
		final Set<String> mappedColumns = prependPrefixes(resultMap.getMappedColumns(),
			upperColumnPrefix);
		//columnNames中保存了数据库所有列的名称，对于自嵌套属性肯定也是将其中的每一个列都放在同一层上
		for (String columnName : columnNames) {
			//列名大写
			final String upperColumnName = columnName.toUpperCase(Locale.ENGLISH);
			//<resultMap/>中配置了columnName
			if (mappedColumns.contains(upperColumnName)) {
				//放入已映射列表
				mappedColumnNames.add(upperColumnName);
			} else {
				//未映射列表
				unmappedColumnNames.add(columnName);
			}
		}
		//以<resultMap/>id+:+列名前缀为key，已映射列表为value
		mappedColumnNamesMap.put(getMapKey(resultMap, columnPrefix), mappedColumnNames);
		//以<resultMap/>id+:+列名前缀为key，未映射列表为value
		unMappedColumnNamesMap.put(getMapKey(resultMap, columnPrefix), unmappedColumnNames);
	}

	/**
	 * 获得在<resultMap/>中明确指明的列名字段映射，比如
	 * <id property="id" column="id" />
	 * <result property="name" column="name" />
	 */
	public List<String> getMappedColumnNames(ResultMap resultMap, String columnPrefix)
		throws SQLException {
		//获取resultMap下所有已映射列名
		List<String> mappedColumnNames = mappedColumnNamesMap
			.get(getMapKey(resultMap, columnPrefix));
		if (mappedColumnNames == null) {
			//没有列名集合先加载处理列名
			loadMappedAndUnmappedColumnNames(resultMap, columnPrefix);
			//再获取
			mappedColumnNames = mappedColumnNamesMap.get(getMapKey(resultMap, columnPrefix));
		}
		return mappedColumnNames;
	}

	/**
	 * 获取在<resultMap/>中没有明确指明的列名字段映射
	 */
	public List<String> getUnmappedColumnNames(ResultMap resultMap, String columnPrefix)
		throws SQLException {
		//获取resultMap下所有未映射列名
		List<String> unMappedColumnNames = unMappedColumnNamesMap
			.get(getMapKey(resultMap, columnPrefix));
		if (unMappedColumnNames == null) {
			//不存在尝试去处理一下
			loadMappedAndUnmappedColumnNames(resultMap, columnPrefix);
			//再获取
			unMappedColumnNames = unMappedColumnNamesMap.get(getMapKey(resultMap, columnPrefix));
		}
		return unMappedColumnNames;
	}

	private String getMapKey(ResultMap resultMap, String columnPrefix) {
		return resultMap.getId() + ":" + columnPrefix;
	}

	/**
	 * 将前缀（如果存在）加在列名前
	 *
	 * @param columnNames 列名列表
	 * @param prefix 前缀
	 */
	private Set<String> prependPrefixes(Set<String> columnNames, String prefix) {
		//如果没有前缀、不存在列名集合直接返回
		if (columnNames == null || columnNames.isEmpty() || prefix == null
			|| prefix.length() == 0) {
			return columnNames;
		}
		final Set<String> prefixed = new HashSet<>();
		// 前缀+列名
		for (String columnName : columnNames) {
			prefixed.add(prefix + columnName);
		}
		return prefixed;
	}

}
