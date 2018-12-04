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
package org.apache.ibatis.scripting.xmltags;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import ognl.Ognl;
import ognl.OgnlException;

import org.apache.ibatis.builder.BuilderException;

/**
 * Caches OGNL parsed expressions.
 *
 * @author Eduardo Macarron
 * @see <a href='http://code.google.com/p/mybatis/issues/detail?id=342'>Issue 342</a>
 */
public final class OgnlCache {

	//ognl访问对象
	private static final OgnlMemberAccess MEMBER_ACCESS = new OgnlMemberAccess();
	private static final OgnlClassResolver CLASS_RESOLVER = new OgnlClassResolver();
	//表达式缓存映射
	private static final Map<String, Object> expressionCache = new ConcurrentHashMap<>();

	private OgnlCache() {
		// Prevent Instantiation of Static Class
	}

	/**
	 * 根据ognl表达式，取出数据中满足表达式的值
	 *
	 * @param expression ognl表达式
	 * @param root 数据
	 */
	public static Object getValue(String expression, Object root) {
		try {
			//根据数据创建ongl上下文
			Map context = Ognl.createDefaultContext(root, MEMBER_ACCESS, CLASS_RESOLVER, null);
			//解析ognl表达式，并从上下文中取出满足条件的数据
			return Ognl.getValue(parseExpression(expression), context, root);
		} catch (OgnlException e) {
			throw new BuilderException(
				"Error evaluating expression '" + expression + "'. Cause: " + e, e);
		}
	}

	/**
	 * 解析ognl表达式，先从缓存中取，取不到先解析再放入缓存
	 *
	 * @param expression ognl表达式
	 */
	private static Object parseExpression(String expression) throws OgnlException {
		Object node = expressionCache.get(expression);
		if (node == null) {
			node = Ognl.parseExpression(expression);
			expressionCache.put(expression, node);
		}
		return node;
	}

}
