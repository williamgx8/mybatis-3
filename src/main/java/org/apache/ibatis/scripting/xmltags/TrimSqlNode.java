/**
 *    Copyright 2009-2018 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.scripting.xmltags;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringTokenizer;

import org.apache.ibatis.session.Configuration;

/**
 * <trim/> 对应sql节点，并且<trim/>是<set/>和<where/>的父节点，有一部分公用
 *
 * @author Clinton Begin
 */
public class TrimSqlNode implements SqlNode {

	//<trim/>下包含的层级关系节点
	private final SqlNode contents;
	//前缀
	private final String prefix;
	//后缀
	private final String suffix;
	//前缀覆盖
	private final List<String> prefixesToOverride;
	//后缀覆盖
	private final List<String> suffixesToOverride;
	private final Configuration configuration;

	public TrimSqlNode(Configuration configuration, SqlNode contents, String prefix,
		String prefixesToOverride, String suffix, String suffixesToOverride) {
		this(configuration, contents, prefix, parseOverrides(prefixesToOverride), suffix,
			parseOverrides(suffixesToOverride));
	}

	protected TrimSqlNode(Configuration configuration, SqlNode contents, String prefix,
		List<String> prefixesToOverride, String suffix, List<String> suffixesToOverride) {
		this.contents = contents;
		this.prefix = prefix;
		this.prefixesToOverride = prefixesToOverride;
		this.suffix = suffix;
		this.suffixesToOverride = suffixesToOverride;
		this.configuration = configuration;
	}

	@Override
	public boolean apply(DynamicContext context) {
		//FilteredDynamicContext会先被传递给下一层子节点处理，其中包含了处理过的sql，再回到上层处理
		FilteredDynamicContext filteredDynamicContext = new FilteredDynamicContext(context);
		//先让子节点处理逻辑
		boolean result = contents.apply(filteredDynamicContext);
		//将该层及所有子层处理的结果进行汇总处理
		filteredDynamicContext.applyAll();
		return result;
	}

	/**
	 * 前/后缀覆盖解析
	 *
	 * @param overrides 覆盖内容
	 */
	private static List<String> parseOverrides(String overrides) {
		if (overrides != null) {
			//|标识或者关系
			final StringTokenizer parser = new StringTokenizer(overrides, "|", false);
			final List<String> list = new ArrayList<>(parser.countTokens());
			//遍历每一种可能，转成大写
			while (parser.hasMoreTokens()) {
				list.add(parser.nextToken().toUpperCase(Locale.ENGLISH));
			}
			return list;
		}
		return Collections.emptyList();
	}

	private class FilteredDynamicContext extends DynamicContext {

		private DynamicContext delegate;
		private boolean prefixApplied;
		private boolean suffixApplied;
		private StringBuilder sqlBuffer;

		public FilteredDynamicContext(DynamicContext delegate) {
			super(configuration, null);
			this.delegate = delegate;
			this.prefixApplied = false;
			this.suffixApplied = false;
			this.sqlBuffer = new StringBuilder();
		}

		/**
		 * 汇总处理当前层和所有子层sql片段
		 */
		public void applyAll() {
			//sqlBuffer中可能包含子层sql片段
			sqlBuffer = new StringBuilder(sqlBuffer.toString().trim());
			/**
			 * 将原始sql片段都变成大写的目的是为了统一进行前后缀配置规则的匹配，不能和原始sql进行匹配是因为，
			 * 原始sql不知道用户写的是大写还是小写，也不能将用户的sql随意替换成统一的模式，因此只能讲前后缀
			 * 规则统一成某种样式，而原始sql用一个副本也统一成和规则相同的样式，才能比较
			 */
			String trimmedUppercaseSql = sqlBuffer.toString().toUpperCase(Locale.ENGLISH);
			if (trimmedUppercaseSql.length() > 0) {
				//处理前缀和后缀
				applyPrefix(sqlBuffer, trimmedUppercaseSql);
				applySuffix(sqlBuffer, trimmedUppercaseSql);
			}
			delegate.appendSql(sqlBuffer.toString());
		}

		@Override
		public Map<String, Object> getBindings() {
			return delegate.getBindings();
		}

		@Override
		public void bind(String name, Object value) {
			delegate.bind(name, value);
		}

		@Override
		public int getUniqueNumber() {
			return delegate.getUniqueNumber();
		}

		@Override
		public void appendSql(String sql) {
			sqlBuffer.append(sql);
		}

		@Override
		public String getSql() {
			return delegate.getSql();
		}

		/**
		 * 处理前缀
		 *
		 * @param sql 待处理的sql片段
		 */
		private void applyPrefix(StringBuilder sql, String trimmedUppercaseSql) {
			//前缀尚未处理
			if (!prefixApplied) {
				//置为已经处理
				prefixApplied = true;
				//处理前缀覆盖
				if (prefixesToOverride != null) {
					//多个前缀覆盖，只可能存在一个匹配的，找到匹配的删除
					for (String toRemove : prefixesToOverride) {
						//因为prefixesToOverride中的数据都已经被转成大写了，所以需要与传进来的trimmedUppercaseSql进行比较
						if (trimmedUppercaseSql.startsWith(toRemove)) {
							sql.delete(0, toRemove.trim().length());
							break;
						}
					}
				}
				//前缀添加
				if (prefix != null) {
					sql.insert(0, " ");
					sql.insert(0, prefix);
				}
			}
		}

		/**
		 * 处理后缀
		 * @param sql 待处理的sql片段
		 * @param trimmedUppercaseSql 已转成大写的Sql片段
		 */
		private void applySuffix(StringBuilder sql, String trimmedUppercaseSql) {
			//后缀尚未处理
			if (!suffixApplied) {
				//标志已经处理
				suffixApplied = true;
				//处理后缀覆盖
				if (suffixesToOverride != null) {
					for (String toRemove : suffixesToOverride) {
						if (trimmedUppercaseSql.endsWith(toRemove) || trimmedUppercaseSql
							.endsWith(toRemove.trim())) {
							int start = sql.length() - toRemove.trim().length();
							int end = sql.length();
							sql.delete(start, end);
							break;
						}
					}
				}
				//处理后缀插入
				if (suffix != null) {
					sql.append(" ");
					sql.append(suffix);
				}
			}
		}

	}

}
