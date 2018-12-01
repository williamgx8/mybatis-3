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
package com.xiaomi.datasource;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

public class ProxyTest {

	static class Target {

		public void run() {
			System.out.println("target run...");
		}
	}


	static  class TargetProxy implements InvocationHandler {


		@Override
		public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
			System.out.println("proxy run...");
			return method.invoke(proxy, args);
		}
	}


	public static void main(String[] args) throws Throwable {
		Target target = new Target();
		Method method = target.getClass().getMethod("run");
		TargetProxy targetProxy = new TargetProxy();
		targetProxy.invoke(target, method, null);
	}
}
