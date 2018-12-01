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
package com.xiaomi.binding;

import static org.junit.Assert.assertEquals;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class MethodHandlesTest {

	@Rule
	public ExpectedException thrown = ExpectedException.none();

	@Test
	public void findVirtual() throws Throwable {
		//第一个参数是返回值类型，如果没有返回值必须写void.class，不能写null
		MethodType methodType = MethodType.methodType(void.class, String.class);
		MethodHandle methodHandle = MethodHandles.lookup()
			.findVirtual(Person.class, "eat", methodType);

		//实例方法不能直接invoke
//		methodHandle.invoke("apple");
		//实例方法需要和某个实例绑定
		methodHandle.bindTo(new Person()).invoke("apple");
	}


	@Test
	public void findStatic() throws Throwable {
		//无参数
		MethodType methodType = MethodType.methodType(int.class);
		MethodHandle methodHandle = MethodHandles.lookup()
			.findStatic(Person.class, "getAge", methodType);
		//直接invoke
		methodHandle.invoke();
		// static方法不能和对象实例绑定再invoke
//		methodHandle.bindTo(new Person()).invoke();
	}


	@Test
	public void lookupTest() throws Throwable {
		MethodHandle publicMethod = MethodHandles.publicLookup()
			.findVirtual(A.class, "sleep", MethodType.methodType(void.class));

		thrown.expect(IllegalAccessException.class);
		//publicLookup只能抓取 public class 内的 public 方法
		MethodHandles.publicLookup()
			.findStatic(Person.class, "getAge", MethodType.methodType(int.class));

	}

	@Test
	public void constructorTest() throws Throwable {
		//构造器的返回值都为void
		MethodHandle constructor = MethodHandles.lookup()
			.findConstructor(Person.class, MethodType.methodType(void.class,
				String.class));
		System.out.println(constructor);

	}


	@Test
	public void fieldTest() throws Throwable {
		MethodHandle genderHandle = MethodHandles.lookup()
			.findGetter(Person.class, "gender", String.class);
		System.out.println(genderHandle);
	}

	@Test
	public void privateMethodTest() throws Throwable {
		//获取private方法依然需要反射的介入
		Method privateMethod = Person.class.getDeclaredMethod("getAddress");
		privateMethod.setAccessible(true);

		MethodHandle methodHandle = MethodHandles.lookup().unreflect(privateMethod);
		String ret = (String) methodHandle.bindTo(new Person()).invoke();

		assertEquals("beijing", ret);
	}

	@Test
	public void invokeAndInvokeWithArguments() throws Throwable {
		Integer age = 12;
		String address = "shanghai";
		Object[] params = {age, address};

		MethodHandle mh = MethodHandles.lookup().findVirtual(Person.class, "info",
			MethodType.methodType(void.class, int.class, String.class));
		mh.bindTo(new Person()).invoke(age, address);
		mh.bindTo(new Person()).invokeWithArguments(age, address);

		//invokeWithArguments可以将多个param放在一起传入，invoke不行
		mh.bindTo(new Person()).invokeWithArguments(params);
//		mh.bindTo(new Person()).invoke(params);
	}


	@Test
	public void findSpecialTest() throws Throwable {
		MethodHandle mh = MethodHandles.lookup()
			.findSpecial(MethodHandlesTest.class, "getAddress", MethodType.methodType(String.class),
				MethodHandlesTest.class);

		//findVirtual也可以访问自身的private
		MethodHandle mh2 = MethodHandles.lookup()
			.findVirtual(MethodHandlesTest.class, "getAddress",
				MethodType.methodType(String.class));

		thrown.expect(IllegalAccessException.class);
		/**
		 * 用findSpecial访问当前类外的类中的private方法，报错
		 * 所以网上很多说findSpecial是用来访问private方法的，这种
		 * 说法有问题
		 * 我立即findSpecial特殊之处在于最后一个参数 specialCaller，
		 * 如果该参数与第一个参数存在父子关系，依然会按照specialCaller类中
		 * 的method调用
		 */
		MethodHandle mh3 = MethodHandles.lookup()
			.findSpecial(Person.class, "getAddress",
				MethodType.methodType(String.class), Person.class);

//		String ret = (String) mh.bindTo(new Person()).invoke();
		String ret = (String) mh.bindTo(this).invoke();
		ret = (String) mh2.bindTo(this).invoke();
		System.out.println(ret);
	}



	static class Person {

		private String name;
		private static int age = 12;
		String gender;


		public Person() {

		}

		public Person(String name) {
			this.name = name;
		}

		public void eat(String food) {
			System.out.println("eat " + food);
		}


		public void sleep() {
			System.out.println("sleep...");
		}


		public static int getAge() {
			System.out.println(" get age method");
			return age;
		}

		public void setAge(int age) {
			Person.age = age;
		}

		private String getAddress() {
			return "beijing";
		}

		public void info(int age, String address) {
			System.out.println("age = " + age + "  address = " + address);
		}
	}

	private String getAddress() {
		return "shanghai";
	}

}

