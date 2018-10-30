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
import org.junit.Test;

public class TypeTest<T> {

	private List<String> list = new ArrayList<>();
	private T[] genericArray;
	private String name = "zhangsan";

	@Test
	public void parameterizedTypeTest1() throws NoSuchFieldException {
		Field field = TypeTest.class.getDeclaredField("name");
		System.out.println(field.getGenericType());

		assertTrue(field.getGenericType() instanceof Class);
	}

	@Test
	public void parameterizedTypeTest2() throws NoSuchFieldException {
		Field field = TypeTest.class.getDeclaredField("list");
		//打印出来的内容是ParameterizedType重写了toString的内容，没有反映出getGenericType是何种类型
		System.out.println(field.getGenericType());

		System.out.println(field.getGenericType() instanceof ParameterizedType);
		ParameterizedType parameterizedType = (ParameterizedType) field.getGenericType();
		System.out.println( parameterizedType.getRawType());
		System.out.println(Arrays.stream(parameterizedType.getActualTypeArguments()).collect(toList()));
	}

	private class CustomHashMap<K extends Comparable & Serializable, V>{
		K key;
		V value;
	}


	@Test
	public void TypeVariableTest() throws  NoSuchFieldException {
		Field key = CustomHashMap.class.getDeclaredField("key");
		Field value = CustomHashMap.class.getDeclaredField("value");

		Type keyType = key.getGenericType();
		Type valueType = value.getGenericType();

		assertTrue(keyType instanceof TypeVariable);
		assertTrue(valueType instanceof TypeVariable);

		assertEquals("K",keyType.getTypeName());
		assertEquals("V", valueType.getTypeName());

		System.out.println(((TypeVariable)keyType).getGenericDeclaration());
		System.out.println(((TypeVariable)valueType).getGenericDeclaration());

		for (Type type : ((TypeVariable) keyType).getBounds()) {
			System.out.println(type.getTypeName());
		}
	}


	@Test
	public void GenericArrayTypeTest() throws NoSuchFieldException {
		Field genericArray = TypeTest.class.getDeclaredField("genericArray");

		Type genericType = genericArray.getGenericType();
		assertTrue(genericType instanceof GenericArrayType);

		Type genericComponentType = ((GenericArrayType) genericType).getGenericComponentType();
		System.out.println(genericComponentType.getTypeName());
	}

}
