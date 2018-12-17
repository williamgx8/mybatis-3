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
package org.apache.ibatis.plugin;

import java.util.Properties;

/**
 * @author Clinton Begin
 */
public interface Interceptor {

	/**
	 * 拦截方法，在执行目标方法时会到这
	 */
	Object intercept(Invocation invocation) throws Throwable;

	/**
	 * 应用插件，生成具有该插件的代理对象
	 */
	Object plugin(Object target);

	/**
	 * 设置拦截器属性
	 */
	void setProperties(Properties properties);

}
