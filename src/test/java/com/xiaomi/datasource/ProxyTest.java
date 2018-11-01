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
