/**
 * Copyright 2009-2016 the original author or authors.
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
package org.apache.ibatis.builder;

import java.util.HashMap;

/**
 * 参数表达式，在Mybatis中可以出现下面这些参数的写法：
 * (id.toString()):VARCHA
 * id,name=value
 * (id.toString()),name=value
 * 所以即便解析到了参数也要处理可能出现的参数表达式
 * <p>
 * Inline parameter expression parser. Supported grammar (simplified):
 *
 * <pre>
 * inline-parameter = (propertyName | expression) oldJdbcType attributes
 * propertyName = /expression language's property navigation path/
 * expression = '(' /expression language's expression/ ')'
 * oldJdbcType = ':' /any valid jdbc type/
 * attributes = (',' attribute)*
 * attribute = name '=' value
 * </pre>
 *
 * @author Frank D. Martinez [mnesarco]
 */
public class ParameterExpression extends HashMap<String, String> {

	private static final long serialVersionUID = -2417552199605158680L;

	public ParameterExpression(String expression) {
		parse(expression);
	}

	/**
	 * 解析参数表达式
	 *
	 * @param expression 参数
	 */
	private void parse(String expression) {
		//跳过空格
		int p = skipWS(expression, 0);
		//如果以(开始，从后面真正的字符开始解析
		if (expression.charAt(p) == '(') {
			expression(expression, p + 1);
		} else {
			//无()解析
			property(expression, p);
		}
	}

	private void expression(String expression, int left) {
		int match = 1;
		int right = left + 1;
		//筛选出()中间的表达式，双指针逼近
		while (match > 0) {
			if (expression.charAt(right) == ')') {
				match--;
			} else if (expression.charAt(right) == '(') {
				match++;
			}
			right++;
		}
		put("expression", expression.substring(left, right - 1));
		//解析表达式中的:后面的jdbcType
		jdbcTypeOpt(expression, right);
	}

	private void property(String expression, int left) {
		if (left < expression.length()) {
			//获取从表达式从left开始到,:的下标，作为右边界
			int right = skipUntil(expression, left, ",:");
			//去除空格保存为property，比如(id.toString()):VARCHA--> id.toString()
			put("property", trimmedStr(expression, left, right));
			//解析:后面的jdbcType
			jdbcTypeOpt(expression, right);
		}
	}

	/**
	 * 跳过参数表达式中的空格
	 *
	 * @param expression 参数表达式
	 * @param p 从哪个index字符开始
	 * @return 跳过空格的开始偏移量
	 */
	private int skipWS(String expression, int p) {
		for (int i = p; i < expression.length(); i++) {
			//从左到右，只要当前不是空格直接返回当前字符所在下标
			if (expression.charAt(i) > 0x20) {
				return i;
			}
		}
		//给出的开始偏移量p已经超过参数表达式总长度，直接返回参数表达式长度
		return expression.length();
	}

	/**
	 * 截取expression中从[p,endChars]之间的字符
	 *
	 * @param expression 表达式
	 * @param p 开始偏移量
	 * @param endChars 结束字符
	 */
	private int skipUntil(String expression, int p, final String endChars) {
		for (int i = p; i < expression.length(); i++) {
			char c = expression.charAt(i);
			//大于-1说明找到了，到此为止，返回
			if (endChars.indexOf(c) > -1) {
				return i;
			}
		}
		return expression.length();
	}

	/**
	 * 从p开始解析出expression中的jdbcType
	 *
	 * @param expression 表达式
	 * @param p 开始偏移量
	 */
	private void jdbcTypeOpt(String expression, int p) {
		//跳过空格
		p = skipWS(expression, p);
		if (p < expression.length()) {
			//:后面肯定就是，直接处理
			if (expression.charAt(p) == ':') {
				jdbcType(expression, p + 1);
			} else if (expression.charAt(p) == ',') {
				//jdbcType也可能在,后写明，还需继续处理,后的数据
				option(expression, p + 1);
			} else {
				throw new BuilderException(
					"Parsing error in {" + expression + "} in position " + p);
			}
		}
	}

	private void jdbcType(String expression, int p) {
		//跳过空格
		int left = skipWS(expression, p);
		//到出现,结束
		int right = skipUntil(expression, left, ",");
		if (right > left) {
			//[:, ,]之间的是jdbcType
			put("jdbcType", trimmedStr(expression, left, right));
		} else {
			throw new BuilderException("Parsing error in {" + expression + "} in position " + p);
		}
		//处理可能出现的,后面的部分
		option(expression, right + 1);
	}

	/**
	 * ,后可能还有各种情况，比如 jdbcType =  VARCHAR,  attr1 = val1 ,  attr2 = val2
	 */
	private void option(String expression, int p) {
		int left = skipWS(expression, p);
		if (left < expression.length()) {
			//截取到 = 的右下标
			int right = skipUntil(expression, left, "=");
			//得到配置的属性名 ，比如 jdbcType = VARCHAR里的jdbcType
			String name = trimmedStr(expression, left, right);
			//属性值开始的下标
			left = right + 1;
			//再到第一个,
			right = skipUntil(expression, left, ",");
			//属性值
			String value = trimmedStr(expression, left, right);
			//设置
			put(name, value);
			//继续递归
			option(expression, right + 1);
		}
	}

	/**
	 * 去除[start, end]范围内str子串的前后端空格
	 */
	private String trimmedStr(String str, int start, int end) {
		while (str.charAt(start) <= 0x20) {
			start++;
		}
		while (str.charAt(end - 1) <= 0x20) {
			end--;
		}
		return start >= end ? "" : str.substring(start, end);
	}

}
