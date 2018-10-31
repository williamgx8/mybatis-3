package com.xiaomi.reflection;

import static java.util.stream.Collectors.toList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.ibatis.reflection.TypeParameterResolver;
import org.junit.Test;

public class TypeTest<T> {

	private List<T> list = new ArrayList<>();//参数化类型  ParameterizedType
	private T[] genericArray; // 参数化类型数组  GenericArrayType
	private String name = "zhangsan"; //普通Class
	private T obj;//类型变量  TypeVariable

	@Test
	public void classTest() throws NoSuchFieldException {
		Field field = TypeTest.class.getDeclaredField("name");
		System.out.println(field.getGenericType());

		assertTrue(field.getGenericType() instanceof Class);
	}

	@Test
	public void parameterizedTypeTest() throws NoSuchFieldException {
		Field field = TypeTest.class.getDeclaredField("list");
		//打印出来的内容是ParameterizedType重写了toString的内容，没有反映出getGenericType是何种类型
		System.out.println(field.getGenericType());

		System.out.println(field.getGenericType() instanceof ParameterizedType);
		ParameterizedType parameterizedType = (ParameterizedType) field.getGenericType();
		System.out.println(parameterizedType.getRawType());
		System.out
			.println(Arrays.stream(parameterizedType.getActualTypeArguments()).collect(toList()));


	}

	private class CustomHashMap<K extends Comparable & Serializable, V> {

		K key;
		V value;

		List<K> keyList;

		public void superClassMethod() {

		}
	}

	private class ChildHashMap extends CustomHashMap<String, String> {

	}

	@Test
	public void methodDeclaredClassTest() throws NoSuchMethodException {
		Method superClassMethod = ChildHashMap.class.getMethod("superClassMethod");
		Class<?> declaringClass = superClassMethod.getDeclaringClass();
		assertEquals(declaringClass,CustomHashMap.class);
	}


	@Test
	public void TypeVariableTest() throws NoSuchFieldException {
		Field key = CustomHashMap.class.getDeclaredField("key");
		Field value = CustomHashMap.class.getDeclaredField("value");

		Type keyType = key.getGenericType();
		Type valueType = value.getGenericType();

		assertTrue(keyType instanceof TypeVariable);
		assertTrue(valueType instanceof TypeVariable);

		assertEquals("K", keyType.getTypeName());
		assertEquals("V", valueType.getTypeName());

		System.out.println(((TypeVariable) keyType).getGenericDeclaration());
		System.out.println(((TypeVariable) valueType).getGenericDeclaration());

		for (Type type : ((TypeVariable) keyType).getBounds()) {
			System.out.println(type.getTypeName());
		}

		ChildHashMap childHashMap = new ChildHashMap();
		TypeParameterResolver.resolveFieldType(key, (Type) childHashMap.getClass());
	}


	@Test
	public void GenericArrayTypeTest() throws NoSuchFieldException {
		Field genericArray = TypeTest.class.getDeclaredField("genericArray");

		Type genericType = genericArray.getGenericType();
		assertTrue(genericType instanceof GenericArrayType);

		Type genericComponentType = ((GenericArrayType) genericType).getGenericComponentType();
		System.out.println(genericComponentType.getTypeName());
	}


	@Test
	public void classActualType() {
		ChildHashMap childHashMap = new ChildHashMap();
		Type genericSuperclass = childHashMap.getClass().getGenericSuperclass();
		assertTrue(genericSuperclass instanceof ParameterizedType);

		ParameterizedType superClass = (ParameterizedType) genericSuperclass;
		// 父类不带泛型的type
		Type parentType = superClass.getRawType();
		assertEquals("com.xiaomi.reflection.TypeTest$CustomHashMap", parentType.getTypeName());

		//父类上定义的泛型
		TypeVariable[] typeParameters = ((Class) parentType).getTypeParameters();
		for (TypeVariable typeParameter : typeParameters) {
			System.out.println(typeParameter.getName());
		}

		// 子类传递给父类泛型的真实类型，通过得到父类的对象得到，而不是根据子类获得
		for (Type actualTypeArgument : superClass.getActualTypeArguments()) {
			System.out.println(actualTypeArgument.getTypeName());
		}
	}

}
