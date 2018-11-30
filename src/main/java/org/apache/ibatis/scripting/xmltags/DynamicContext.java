/**
 * Copyright 2009-2015 the original author or authors.
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
package org.apache.ibatis.scripting.xmltags;

import java.util.HashMap;
import java.util.Map;

import ognl.OgnlContext;
import ognl.OgnlException;
import ognl.OgnlRuntime;
import ognl.PropertyAccessor;

import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.Configuration;

/**
 * 动态 SQL ，用于每次执行 SQL 操作时，记录动态 SQL 处理后的最终 SQL 字符串
 *
 * @author Clinton Begin
 */
public class DynamicContext {

	//对应存放请求参数的特殊key
	public static final String PARAMETER_OBJECT_KEY = "_parameter";
	public static final String DATABASE_ID_KEY = "_databaseId";

	static {
		OgnlRuntime.setPropertyAccessor(ContextMap.class, new ContextAccessor());
	}

	//上下文参数集合
	private final ContextMap bindings;
	//保存最终处理完成的Sql
	private final StringBuilder sqlBuilder = new StringBuilder();
	private int uniqueNumber = 0;

	public DynamicContext(Configuration configuration, Object parameterObject) {
		//mybatis在运行流程中parameterObject绝大部分情况都经过了处理，是一个Map
		if (parameterObject != null && !(parameterObject instanceof Map)) {
			//生成MetaObject，并以此为额外的Map，生成自定义ognl上下文容器
			MetaObject metaObject = configuration.newMetaObject(parameterObject);
			bindings = new ContextMap(metaObject);
		} else {
			bindings = new ContextMap(null);
		}
		//绑定两个固定key-value对
		bindings.put(PARAMETER_OBJECT_KEY, parameterObject);
		bindings.put(DATABASE_ID_KEY, configuration.getDatabaseId());
	}

	public Map<String, Object> getBindings() {
		return bindings;
	}

	/**
	 * 往上下文塞key-value对
	 */
	public void bind(String name, Object value) {
		bindings.put(name, value);
	}

	/**
	 * 拼装sql
	 *
	 * @param sql sql片段
	 */
	public void appendSql(String sql) {
		sqlBuilder.append(sql);
		sqlBuilder.append(" ");
	}

	public String getSql() {
		return sqlBuilder.toString().trim();
	}

	public int getUniqueNumber() {
		return uniqueNumber++;
	}

	/**
	 * ognl表达式中保存上下文数据的Map
	 */
	static class ContextMap extends HashMap<String, Object> {

		private static final long serialVersionUID = 2977601501966151582L;

		private MetaObject parameterMetaObject;

		public ContextMap(MetaObject parameterMetaObject) {
			this.parameterMetaObject = parameterMetaObject;
		}

		/**
		 * ognl上下文数据获取分为两部分：1.普通的Map根据key获取value；2.第一步后未取到数据判断构建ognl上下文时
		 * 是否传递参数元数据ParameterMetaObject，如果存在在元数据中再查找一次
		 */
		@Override
		public Object get(Object key) {
			String strKey = (String) key;
			if (super.containsKey(strKey)) {
				return super.get(strKey);
			}

			if (parameterMetaObject != null) {
				// issue #61 do not modify the context when reading
				return parameterMetaObject.getValue(strKey);
			}

			return null;
		}
	}

	/**
	 * ContextMap的访问器，ognl内部通过它访问ContextMap
	 */
	static class ContextAccessor implements PropertyAccessor {

		/**
		 * 从ognl上下文中获取name对应的值
		 *
		 * @param target 实际上就是ContextMap
		 * @param name 要获取的key名称
		 */
		@Override
		public Object getProperty(Map context, Object target, Object name)
			throws OgnlException {
			Map map = (Map) target;

			//先从用户自定义的ContextMap中获取
			Object result = map.get(name);
			if (map.containsKey(name) || result != null) {
				return result;
			}
			//再获取DynamicContext内置的key对应的Map，该Map在mybatis执行时一般都是一个Map
			Object parameterObject = map.get(PARAMETER_OBJECT_KEY);
			if (parameterObject instanceof Map) {
				return ((Map) parameterObject).get(name);
			}

			return null;
		}

		@Override
		public void setProperty(Map context, Object target, Object name, Object value)
			throws OgnlException {
			//放入自定义ContextMap
			Map<Object, Object> map = (Map<Object, Object>) target;
			map.put(name, value);
		}

		@Override
		public String getSourceAccessor(OgnlContext arg0, Object arg1, Object arg2) {
			return null;
		}

		@Override
		public String getSourceSetter(OgnlContext arg0, Object arg1, Object arg2) {
			return null;
		}
	}
}