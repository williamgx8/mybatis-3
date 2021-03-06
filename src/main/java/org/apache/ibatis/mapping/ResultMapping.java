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
package org.apache.ibatis.mapping;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;
import org.apache.ibatis.type.TypeHandlerRegistry;

/**
 * @author Clinton Begin
 */
public class ResultMapping {

	// 核心配置对象
	private Configuration configuration;
	// 属性名，对应元素的property属性
	private String property;
	// 字段名，对应元素的column属性
	private String column;
	// 属性的java类型，对应元素的javaType属性
	private Class<?> javaType;
	// 字段的jdbc类型，对应元素的jdbcType属性
	private JdbcType jdbcType;
	// 类型处理器，对应元素的typeHandler属性
	private TypeHandler<?> typeHandler;

	/**
	 * 当前resultMapping对应标签的结果可能需要另一个resultMap得到，
	 * 此时嵌套resultMap会立即得到结果，并封装到主resultMap列对应的字段中
	 */
	private String nestedResultMapId;
	/**
	 * 当前resultMapping对应标签的结果可能对应select属性，该属性指向另一条
	 * 查询语句，需要再调用一次查询才能获得该列的值，nestedQueryId一般对应
	 * 另一条查询语句的<select id=""/>中的id，如果开启了延迟加载，该值在
	 * 查询其他主属性时并不会立即查询，当使用该列值时才会真正触发查询动作
	 */
	private String nestedQueryId;
	// 对应元素的notNullColumn属性拆分后的结果
	private Set<String> notNullColumns;
	// 对应元素的columnPrefix属性
	private String columnPrefix;
	// 处理后的标志，标志共两个：id和constructor
	private List<ResultFlag> flags;
	// 对应元素的column属性拆分后生成的结果，比如column= ” {prop1=col1,prop2=col2} ”，composites.size()>0会时column为null
	private List<ResultMapping> composites;
	// 对应元素的resultSet属性
	private String resultSet;
	// 对应元素的foreignColumn属性
	private String foreignColumn;
	// 是否延迟加载，对应元素的fetchType属性值，lazy则为true否则为false
	// 也直接从配置对象中读取
	private boolean lazy;

	ResultMapping() {
	}

	public static class Builder {

		private ResultMapping resultMapping = new ResultMapping();

		public Builder(Configuration configuration, String property, String column,
			TypeHandler<?> typeHandler) {
			this(configuration, property);
			resultMapping.column = column;
			resultMapping.typeHandler = typeHandler;
		}

		public Builder(Configuration configuration, String property, String column,
			Class<?> javaType) {
			this(configuration, property);
			resultMapping.column = column;
			resultMapping.javaType = javaType;
		}

		public Builder(Configuration configuration, String property) {
			resultMapping.configuration = configuration;
			resultMapping.property = property;
			resultMapping.flags = new ArrayList<>();
			resultMapping.composites = new ArrayList<>();
			resultMapping.lazy = configuration.isLazyLoadingEnabled();
		}

		public Builder javaType(Class<?> javaType) {
			resultMapping.javaType = javaType;
			return this;
		}

		public Builder jdbcType(JdbcType jdbcType) {
			resultMapping.jdbcType = jdbcType;
			return this;
		}

		public Builder nestedResultMapId(String nestedResultMapId) {
			resultMapping.nestedResultMapId = nestedResultMapId;
			return this;
		}

		public Builder nestedQueryId(String nestedQueryId) {
			resultMapping.nestedQueryId = nestedQueryId;
			return this;
		}

		public Builder resultSet(String resultSet) {
			resultMapping.resultSet = resultSet;
			return this;
		}

		public Builder foreignColumn(String foreignColumn) {
			resultMapping.foreignColumn = foreignColumn;
			return this;
		}

		public Builder notNullColumns(Set<String> notNullColumns) {
			resultMapping.notNullColumns = notNullColumns;
			return this;
		}

		public Builder columnPrefix(String columnPrefix) {
			resultMapping.columnPrefix = columnPrefix;
			return this;
		}

		public Builder flags(List<ResultFlag> flags) {
			resultMapping.flags = flags;
			return this;
		}

		public Builder typeHandler(TypeHandler<?> typeHandler) {
			resultMapping.typeHandler = typeHandler;
			return this;
		}

		public Builder composites(List<ResultMapping> composites) {
			resultMapping.composites = composites;
			return this;
		}

		public Builder lazy(boolean lazy) {
			resultMapping.lazy = lazy;
			return this;
		}

		public ResultMapping build() {
			// lock down collections
			resultMapping.flags = Collections.unmodifiableList(resultMapping.flags);
			resultMapping.composites = Collections.unmodifiableList(resultMapping.composites);
			resolveTypeHandler();
			validate();
			return resultMapping;
		}

		private void validate() {
			// Issue #697: cannot define both nestedQueryId and nestedResultMapId
			if (resultMapping.nestedQueryId != null && resultMapping.nestedResultMapId != null) {
				throw new IllegalStateException(
					"Cannot define both nestedQueryId and nestedResultMapId in property "
						+ resultMapping.property);
			}
			// Issue #5: there should be no mappings without typehandler
			if (resultMapping.nestedQueryId == null && resultMapping.nestedResultMapId == null
				&& resultMapping.typeHandler == null) {
				throw new IllegalStateException(
					"No typehandler found for property " + resultMapping.property);
			}
			// Issue #4 and GH #39: column is optional only in nested resultmaps but not in the rest
			if (resultMapping.nestedResultMapId == null && resultMapping.column == null
				&& resultMapping.composites.isEmpty()) {
				throw new IllegalStateException(
					"Mapping is missing column attribute for property " + resultMapping.property);
			}
			if (resultMapping.getResultSet() != null) {
				int numColumns = 0;
				if (resultMapping.column != null) {
					numColumns = resultMapping.column.split(",").length;
				}
				int numForeignColumns = 0;
				if (resultMapping.foreignColumn != null) {
					numForeignColumns = resultMapping.foreignColumn.split(",").length;
				}
				if (numColumns != numForeignColumns) {
					throw new IllegalStateException(
						"There should be the same number of columns and foreignColumns in property "
							+ resultMapping.property);
				}
			}
		}

		private void resolveTypeHandler() {
			if (resultMapping.typeHandler == null && resultMapping.javaType != null) {
				Configuration configuration = resultMapping.configuration;
				TypeHandlerRegistry typeHandlerRegistry = configuration.getTypeHandlerRegistry();
				resultMapping.typeHandler = typeHandlerRegistry
					.getTypeHandler(resultMapping.javaType, resultMapping.jdbcType);
			}
		}

		public Builder column(String column) {
			resultMapping.column = column;
			return this;
		}
	}

	public String getProperty() {
		return property;
	}

	public String getColumn() {
		return column;
	}

	public Class<?> getJavaType() {
		return javaType;
	}

	public JdbcType getJdbcType() {
		return jdbcType;
	}

	public TypeHandler<?> getTypeHandler() {
		return typeHandler;
	}

	public String getNestedResultMapId() {
		return nestedResultMapId;
	}

	public String getNestedQueryId() {
		return nestedQueryId;
	}

	public Set<String> getNotNullColumns() {
		return notNullColumns;
	}

	public String getColumnPrefix() {
		return columnPrefix;
	}

	public List<ResultFlag> getFlags() {
		return flags;
	}

	public List<ResultMapping> getComposites() {
		return composites;
	}

	public boolean isCompositeResult() {
		return this.composites != null && !this.composites.isEmpty();
	}

	public String getResultSet() {
		return this.resultSet;
	}

	public String getForeignColumn() {
		return foreignColumn;
	}

	public void setForeignColumn(String foreignColumn) {
		this.foreignColumn = foreignColumn;
	}

	public boolean isLazy() {
		return lazy;
	}

	public void setLazy(boolean lazy) {
		this.lazy = lazy;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || getClass() != o.getClass()) {
			return false;
		}

		ResultMapping that = (ResultMapping) o;

		if (property == null || !property.equals(that.property)) {
			return false;
		}

		return true;
	}

	@Override
	public int hashCode() {
		if (property != null) {
			return property.hashCode();
		} else if (column != null) {
			return column.hashCode();
		} else {
			return 0;
		}
	}

	@Override
	public String toString() {
		final StringBuilder sb = new StringBuilder("ResultMapping{");
		//sb.append("configuration=").append(configuration); // configuration doesn't have a useful .toString()
		sb.append("property='").append(property).append('\'');
		sb.append(", column='").append(column).append('\'');
		sb.append(", javaType=").append(javaType);
		sb.append(", jdbcType=").append(jdbcType);
		//sb.append(", typeHandler=").append(typeHandler); // typeHandler also doesn't have a useful .toString()
		sb.append(", nestedResultMapId='").append(nestedResultMapId).append('\'');
		sb.append(", nestedQueryId='").append(nestedQueryId).append('\'');
		sb.append(", notNullColumns=").append(notNullColumns);
		sb.append(", columnPrefix='").append(columnPrefix).append('\'');
		sb.append(", flags=").append(flags);
		sb.append(", composites=").append(composites);
		sb.append(", resultSet='").append(resultSet).append('\'');
		sb.append(", foreignColumn='").append(foreignColumn).append('\'');
		sb.append(", lazy=").append(lazy);
		sb.append('}');
		return sb.toString();
	}

}
