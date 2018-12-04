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

import java.util.regex.Pattern;

import org.apache.ibatis.parsing.GenericTokenParser;
import org.apache.ibatis.parsing.TokenHandler;
import org.apache.ibatis.scripting.ScriptingException;
import org.apache.ibatis.type.SimpleTypeRegistry;

/**
 * 文本sql节点对象，其中可能存在占位符
 *
 * @author Clinton Begin
 */
public class TextSqlNode implements SqlNode {

	private final String text;
	private final Pattern injectionFilter;

	public TextSqlNode(String text) {
		this(text, null);
	}

	public TextSqlNode(String text, Pattern injectionFilter) {
		this.text = text;
		this.injectionFilter = injectionFilter;
	}

	/**
	 * 判断该节点sql中是否存在${}占位符
	 */
	public boolean isDynamic() {
		//${}占位符解析器，该解析器只是用来判断sql中是否存在${}，存在其内部的dynamic字段为True
		DynamicCheckerTokenParser checker = new DynamicCheckerTokenParser();
		GenericTokenParser parser = createParser(checker);
		//解析占位符为真正的值
		parser.parse(text);
		//返回是否动态sql
		return checker.isDynamic();
	}

	@Override
	public boolean apply(DynamicContext context) {
		//通过BindingTokenParser处理 ${} 占位符
		GenericTokenParser parser = createParser(new BindingTokenParser(context, injectionFilter));
		//解析完成${}后将sql片段放入上下文中，此时sql中不存在${}，但可能还存在#{}占位符
		context.appendSql(parser.parse(text));
		return true;
	}

	/**
	 * 创建${}占位符解析器，handler用于处理${}内包裹的内容
	 *
	 * @param handler 处理${}包裹内容的处理器
	 */
	private GenericTokenParser createParser(TokenHandler handler) {
		return new GenericTokenParser("${", "}", handler);
	}

	private static class BindingTokenParser implements TokenHandler {

		private DynamicContext context;
		private Pattern injectionFilter;

		public BindingTokenParser(DynamicContext context, Pattern injectionFilter) {
			this.context = context;
			this.injectionFilter = injectionFilter;
		}

		@Override
		public String handleToken(String content) {
			//取出上下文中_parameter参数的值，该值封装了所有的参数
			Object parameter = context.getBindings().get("_parameter");
			if (parameter == null) {
				context.getBindings().put("value", null);
			} else if (SimpleTypeRegistry.isSimpleType(parameter.getClass())) {
				//如果是简单类型放入上下文中，key为value
				context.getBindings().put("value", parameter);
			}
			//参数为复杂类型，根据ognl表达式从复杂类型中得到${}对应的值
			Object value = OgnlCache.getValue(content, context.getBindings());
			String srtValue = (value == null ? ""
				: String.valueOf(value)); // issue #274 return "" instead of "null"
			//处理需要过滤的内容
			checkInjection(srtValue);
			//返回解析出的值
			return srtValue;
		}

		private void checkInjection(String value) {
			if (injectionFilter != null && !injectionFilter.matcher(value).matches()) {
				throw new ScriptingException(
					"Invalid input. Please conform to regex" + injectionFilter.pattern());
			}
		}
	}

	private static class DynamicCheckerTokenParser implements TokenHandler {

		private boolean isDynamic;

		public DynamicCheckerTokenParser() {
			// Prevent Synthetic Access
		}

		public boolean isDynamic() {
			return isDynamic;
		}

		@Override
		public String handleToken(String content) {
			this.isDynamic = true;
			return null;
		}
	}

}