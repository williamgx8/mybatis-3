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

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.builder.BuilderException;

/**
 * @author Clinton Begin
 */
public class ExpressionEvaluator {

	/**
	 * 判断参数是否满足表达式
	 *
	 * @param expression 表达式
	 * @param parameterObject 参数
	 * @return 是否匹配
	 */
	public boolean evaluateBoolean(String expression, Object parameterObject) {
		Object value = OgnlCache.getValue(expression, parameterObject);
		if (value instanceof Boolean) {
			return (Boolean) value;
		}
		if (value instanceof Number) {
			return new BigDecimal(String.valueOf(value)).compareTo(BigDecimal.ZERO) != 0;
		}
		return value != null;
	}

	/**
	 * 判断参数和表达式是否匹配，比如在<foreach/>标签中collection属性名称和真正传递的参数是否匹配，
	 * 并取出匹配的值
	 *
	 * @param expression 表达式值
	 * @param parameterObject 封装了参数的对象
	 */
	public Iterable<?> evaluateIterable(String expression, Object parameterObject) {
		//根据表达式取出参数值
		Object value = OgnlCache.getValue(expression, parameterObject);
		//参数为空却有表达式值，有问题
		if (value == null) {
			throw new BuilderException(
				"The expression '" + expression + "' evaluated to a null value.");
		}
		//自定义collection和list都是Iterable，合法返回
		if (value instanceof Iterable) {
			return (Iterable<?>) value;
		}
		//array数组类型，转成list返回
		if (value.getClass().isArray()) {
			// the array may be primitive, so Arrays.asList() may throw
			// a ClassCastException (issue 209).  Do the work manually
			// Curse primitives! :) (JGB)
			int size = Array.getLength(value);
			List<Object> answer = new ArrayList<>();
			for (int i = 0; i < size; i++) {
				Object o = Array.get(value, i);
				answer.add(o);
			}
			return answer;
		}
		//map类型，返回entry set
		if (value instanceof Map) {
			return ((Map) value).entrySet();
		}
		throw new BuilderException(
			"Error evaluating expression '" + expression + "'.  Return value (" + value
				+ ") was not iterable.");
	}

}
