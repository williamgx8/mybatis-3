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
		//第一个元素需要加上open中配置的内容，内部会将prefixApplied置为true
		applyOpen(context);
		int i = 0;
		for (Object o : iterable) {
			//先暂存一下context，由于下面要处理前后缀会将context变成PrefixedContext
			DynamicContext oldContext = context;
			//如果是第一个元素，或者元素间没有分隔符
			if (first || separator == null) {
				context = new PrefixedContext(context, "");
			} else {
				context = new PrefixedContext(context, separator);
			}
			//唯一数不清楚有什么用
			int uniqueNumber = context.getUniqueNumber();
			// Issue #709
			if (o instanceof Map.Entry) {
				@SuppressWarnings("unchecked")
				Map.Entry<Object, Object> mapEntry = (Map.Entry<Object, Object>) o;
				applyIndex(context, mapEntry.getKey(), uniqueNumber);
				applyItem(context, mapEntry.getValue(), uniqueNumber);
			} else {
				//如果配置了index属性，且ognl表达式中存在index对应的位置，进行处理
				applyIndex(context, i, uniqueNumber);
				//处理每一个元素item
				applyItem(context, o, uniqueNumber);
			}
			//将sql片段中的#{item}等内容换成applyItem中生成的内部的item名称
			contents.apply(
				new FilteredDynamicContext(configuration, context, index, item, uniqueNumber));
			//不是第一个元素了
			if (first) {
				first = !((PrefixedContext) context).isPrefixApplied();
			}
			context = oldContext;
			i++;
		}
		//处理close属性字符
		applyClose(context);
		//移除上下文中以item属性名和index属性名为key的map，只保留根据两者生成的mybatis内部唯一标识为key的那一组
		context.getBindings().remove(item);
		context.getBindings().remove(index);
		return true;
	}

	/**
	 * 处理index属性
	 *
	 * @param context 上下文对象
	 */
	private void applyIndex(DynamicContext context, Object o, int i) {
		//index属性存在才需要处理
		if (index != null) {
			//分为两部分，1. key为<foreach/>上index属性名，value为index值
			context.bind(index, o);
			//2. key为mybatis内部存储的值，value为index的值
			context.bind(itemizeItem(index, i), o);
		}
	}

	/**
	 * 处理每一个item属性对应sql片段中的ognl表达式
	 *
	 * @param context 上下文
	 * @param o 每一个遍历到的真实参数
	 * @param i 唯一标示
	 */
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

	/**
	 * 给sql片段加上总的后缀
	 */
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

		/**
		 * 如果sql片段中存在#{}占位符，替换成mybatis内部唯一的标识
		 *
		 * @param sql sql片段
		 */
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

		/**
		 * 重写父类DynamicContext的appendSql，主要为了处理前缀标识
		 *
		 * @param sql sql片段
		 */
		@Override
		public void appendSql(String sql) {
			//如果前缀还未被处理，且sql还没有（说明刚开始）
			if (!prefixApplied && sql != null && sql.trim().length() > 0) {
				//加上前缀
				delegate.appendSql(prefix);
				//将前缀处理置为true
				prefixApplied = true;
			}
			//再交给父类做真正的sql追加
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
