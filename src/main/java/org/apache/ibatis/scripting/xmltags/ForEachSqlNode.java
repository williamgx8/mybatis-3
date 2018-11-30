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

import org.apache.ibatis.parsing.GenericTokenParser;
import org.apache.ibatis.session.Configuration;

/**
 * <foreach/> 节点SqlNode实例
 *
 * @author Clinton Begin
 */
public class ForEachSqlNode implements SqlNode {

	public static final String ITEM_PREFIX = "__frch_";
	//collection属性对应的遍历表达式名称
	private final ExpressionEvaluator evaluator;
	/**
	 * 对应collection属性，该属性有三种取名方式：
	 * 1. 如果传入的是单参数且参数类型是一个List的时候，collection属性值为必须是list，不是你list的名字，就是list
	 * 2. 如果传入的是单参数且参数类型是一个array数组的时候，collection的属性值为array，不是你数组的名字，就是array
	 * 3. 如果传入的参数是多个的时候，或者是以一个对象为参数，collection的属性值就要取你自己定义的名字了，不管是list类型还是array类型，
	 * 比如传入参数为Student，其中有一个List名称为friends，要在<foreach/>中遍历这个List，那么collection的值就必须是friends
	 * 4. Map类型，map中存放key对应这一个集合
	 */
	private final String collectionExpression;
	//<foreach/>内的节点内容，可能是个文本，也能是其他节点，所以都可以封装成SqlNode
	private final SqlNode contents;
	//遍历前添加的内容
	private final String open;
	//最后添加的内容
	private final String close;
	//每一项的分隔符
	private final String separator;
	//item属性，对于集合代表每个元素，对于Map代表value
	private final String item;
	//index属性，对于集合代表下标，对于Map代表key
	private final String index;
	private final Configuration configuration;

	public ForEachSqlNode(Configuration configuration, SqlNode contents,
		String collectionExpression, String index, String item, String open, String close,
		String separator) {
		this.evaluator = new ExpressionEvaluator();
		this.collectionExpression = collectionExpression;
		this.contents = contents;
		this.open = open;
		this.close = close;
		this.separator = separator;
		this.index = index;
		this.item = item;
		this.configuration = configuration;
	}

	@Override
	public boolean apply(DynamicContext context) {
		//获得上下文中的绑定的参数数据
		Map<String, Object> bindings = context.getBindings();
		//判断参数是否符合collection定义的名字，符合将真正的参数取出
		final Iterable<?> iterable = evaluator.evaluateIterable(collectionExpression, bindings);
		if (!iterable.iterator().hasNext()) {
			return true;
		}
		boolean first = true;
		//第一个元素需要加上open中配置的内容
		applyOpen(context);
		int i = 0;
		for (Object o : iterable) {
			DynamicContext oldContext = context;
			//如果是第一个元素，或者元素间没有分隔符
			if (first || separator == null) {
				context = new PrefixedContext(context, "");
			} else {
				context = new PrefixedContext(context, separator);
			}
			int uniqueNumber = context.getUniqueNumber();
			// Issue #709
			if (o instanceof Map.Entry) {
				@SuppressWarnings("unchecked")
				Map.Entry<Object, Object> mapEntry = (Map.Entry<Object, Object>) o;
				applyIndex(context, mapEntry.getKey(), uniqueNumber);
				applyItem(context, mapEntry.getValue(), uniqueNumber);
			} else {
				applyIndex(context, i, uniqueNumber);
				applyItem(context, o, uniqueNumber);
			}
			contents.apply(
				new FilteredDynamicContext(configuration, context, index, item, uniqueNumber));
			if (first) {
				first = !((PrefixedContext) context).isPrefixApplied();
			}
			context = oldContext;
			i++;
		}
		applyClose(context);
		context.getBindings().remove(item);
		context.getBindings().remove(index);
		return true;
	}

	private void applyIndex(DynamicContext context, Object o, int i) {
		if (index != null) {
			context.bind(index, o);
			context.bind(itemizeItem(index, i), o);
		}
	}

	private void applyItem(DynamicContext context, Object o, int i) {
		if (item != null) {
			context.bind(item, o);
			context.bind(itemizeItem(item, i), o);
		}
	}

	/**
	 * 给sql片段加上open属性配置的前缀
	 */
	private void applyOpen(DynamicContext context) {
		if (open != null) {
			context.appendSql(open);
		}
	}

	private void applyClose(DynamicContext context) {
		if (close != null) {
			context.appendSql(close);
		}
	}

	private static String itemizeItem(String item, int i) {
		return ITEM_PREFIX + item + "_" + i;
	}

	private static class FilteredDynamicContext extends DynamicContext {

		private final DynamicContext delegate;
		private final int index;
		private final String itemIndex;
		private final String item;

		public FilteredDynamicContext(Configuration configuration, DynamicContext delegate,
			String itemIndex, String item, int i) {
			super(configuration, null);
			this.delegate = delegate;
			this.index = i;
			this.itemIndex = itemIndex;
			this.item = item;
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
		public String getSql() {
			return delegate.getSql();
		}

		@Override
		public void appendSql(String sql) {
			GenericTokenParser parser = new GenericTokenParser("#{", "}", content -> {
				String newContent = content
					.replaceFirst("^\\s*" + item + "(?![^.,:\\s])", itemizeItem(item, index));
				if (itemIndex != null && newContent.equals(content)) {
					newContent = content.replaceFirst("^\\s*" + itemIndex + "(?![^.,:\\s])",
						itemizeItem(itemIndex, index));
				}
				return "#{" + newContent + "}";
			});

			delegate.appendSql(parser.parse(sql));
		}

		@Override
		public int getUniqueNumber() {
			return delegate.getUniqueNumber();
		}

	}


	private class PrefixedContext extends DynamicContext {

		private final DynamicContext delegate;
		private final String prefix;
		private boolean prefixApplied;

		public PrefixedContext(DynamicContext delegate, String prefix) {
			super(configuration, null);
			this.delegate = delegate;
			this.prefix = prefix;
			this.prefixApplied = false;
		}

		public boolean isPrefixApplied() {
			return prefixApplied;
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
		public void appendSql(String sql) {
			if (!prefixApplied && sql != null && sql.trim().length() > 0) {
				delegate.appendSql(prefix);
				prefixApplied = true;
			}
			delegate.appendSql(sql);
		}

		@Override
		public String getSql() {
			return delegate.getSql();
		}

		@Override
		public int getUniqueNumber() {
			return delegate.getUniqueNumber();
		}
	}

}
