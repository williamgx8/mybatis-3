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
package org.apache.ibatis.reflection;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.Arrays;

/**
 * 类型Type参数Parameter解析器
 * 入口函数有三种：
 * 1. resolveFieldType(Field, Type)解析Type类中实际的Field类型
 * 2. resolveReturnType(Method, Type)解析Type类中实际Method返回值类型
 * 3. resolveParamTypes(Method, Type)解析Type类中Method参数真实类型
 * 无论对于Field、Param还是Return从Type的分类上又可分为四种：
 * 1. ParameterizedType 带泛型参数的类型  比如List<String>、Map<K,V>
 * 2. Class  普通类型   int、Double、POJO
 * 3. TypeVariable  类型变量  比如 T、K等单纯的泛型
 * 4. GenericArrayType  泛型数组   比如  T[]等
 * 从入口函数进入，根据type的不同又分为四种类型单独处理`
 *
 * @author Iwao AVE!
 */
public class TypeParameterResolver {

	/**
	 * 解析field在srcType类中的实际类型
	 *
	 * @return The field type as {@link Type}. If it has type parameters in the declaration,<br>
	 * they will be resolved to the actual runtime {@link Type}s.
	 */
	public static Type resolveFieldType(Field field, Type srcType) {
		//获得Field的Type类型
		Type fieldType = field.getGenericType();
		//Field声明所在的class，一般是srcType本身或者父类/接口
		Class<?> declaringClass = field.getDeclaringClass();
		return resolveType(fieldType, srcType, declaringClass);
	}

	/**
	 * 解析在srcType中方法method真正的返回类型
	 * @return The return type of the method as {@link Type}. If it has type parameters in the declaration,<br>
	 * they will be resolved to the actual runtime {@link Type}s.
	 */
	public static Type resolveReturnType(Method method, Type srcType) {
		// 获取method带泛型的返回值类型
		Type returnType = method.getGenericReturnType();
		// method声明的class，最近原则
		Class<?> declaringClass = method.getDeclaringClass();
		return resolveType(returnType, srcType, declaringClass);
	}

	/**
	 * 解析srcType类型的对象内method的参数类型列表
	 * 实际上如果是普通的POJO，可以不用这么麻烦，但是当遇到父子类、泛型参数等情况，就需要这里特殊的处理。
	 * 比如method可能是父类中某个方法的定义，而该参数是泛型定义，需要在srcType对应的实现类中真正解析出
	 * method实现的参数类型
	 *
	 * @param method 可能是需要解析类中定义的方法，也可能是在其父类中定义的方法
	 * @param srcType 需要解析的那个类的类型
	 */
	public static Type[] resolveParamTypes(Method method, Type srcType) {
		//获得方法定义时的参数列表
		Type[] paramTypes = method.getGenericParameterTypes();
		//方法定义所在类
		Class<?> declaringClass = method.getDeclaringClass();
		Type[] result = new Type[paramTypes.length];
		for (int i = 0; i < paramTypes.length; i++) {
			result[i] = resolveType(paramTypes[i], srcType, declaringClass);
		}
		return result;
	}

	private static Type resolveType(Type type, Type srcType, Class<?> declaringClass) {
		// 和泛型相关一般为TypeVariable，比如T
		if (type instanceof TypeVariable) {
			return resolveTypeVar((TypeVariable<?>) type, srcType, declaringClass);
		} else if (type instanceof ParameterizedType) {
			//参数中带泛型，比如List<T>
			return resolveParameterizedType((ParameterizedType) type, srcType, declaringClass);
		} else if (type instanceof GenericArrayType) {
			//泛型数组
			return resolveGenericArrayType((GenericArrayType) type, srcType, declaringClass);
		} else {
			return type;
		}
	}

	private static Type resolveGenericArrayType(GenericArrayType genericArrayType, Type srcType,
		Class<?> declaringClass) {
		Type componentType = genericArrayType.getGenericComponentType();
		Type resolvedComponentType = null;
		if (componentType instanceof TypeVariable) {
			resolvedComponentType = resolveTypeVar((TypeVariable<?>) componentType, srcType,
				declaringClass);
		} else if (componentType instanceof GenericArrayType) {
			resolvedComponentType = resolveGenericArrayType((GenericArrayType) componentType,
				srcType, declaringClass);
		} else if (componentType instanceof ParameterizedType) {
			resolvedComponentType = resolveParameterizedType((ParameterizedType) componentType,
				srcType, declaringClass);
		}
		if (resolvedComponentType instanceof Class) {
			return Array.newInstance((Class<?>) resolvedComponentType, 0).getClass();
		} else {
			return new GenericArrayTypeImpl(resolvedComponentType);
		}
	}

	/**
	 * 解析参数类型在当前类srcType和声明类declaringClass情况下的真实类型
	 * @param parameterizedType
	 * @param srcType
	 * @param declaringClass
	 * @return
	 */
	private static ParameterizedType resolveParameterizedType(ParameterizedType parameterizedType,
		Type srcType, Class<?> declaringClass) {
		//除去泛型的原始类型
		Class<?> rawType = (Class<?>) parameterizedType.getRawType();
		//泛型<>中所有的类型
		Type[] typeArgs = parameterizedType.getActualTypeArguments();
		Type[] args = new Type[typeArgs.length];
		//每一个泛型类型根据type的不同进一步处理
		for (int i = 0; i < typeArgs.length; i++) {
			if (typeArgs[i] instanceof TypeVariable) {
				args[i] = resolveTypeVar((TypeVariable<?>) typeArgs[i], srcType, declaringClass);
			} else if (typeArgs[i] instanceof ParameterizedType) {
				args[i] = resolveParameterizedType((ParameterizedType) typeArgs[i], srcType,
					declaringClass);
			} else if (typeArgs[i] instanceof WildcardType) {
				args[i] = resolveWildcardType((WildcardType) typeArgs[i], srcType, declaringClass);
			} else {
				args[i] = typeArgs[i];
			}
		}
		//将解析完成的结果封装返回
		return new ParameterizedTypeImpl(rawType, null, args);
	}

	private static Type resolveWildcardType(WildcardType wildcardType, Type srcType,
		Class<?> declaringClass) {
		Type[] lowerBounds = resolveWildcardTypeBounds(wildcardType.getLowerBounds(), srcType,
			declaringClass);
		Type[] upperBounds = resolveWildcardTypeBounds(wildcardType.getUpperBounds(), srcType,
			declaringClass);
		return new WildcardTypeImpl(lowerBounds, upperBounds);
	}

	private static Type[] resolveWildcardTypeBounds(Type[] bounds, Type srcType,
		Class<?> declaringClass) {
		Type[] result = new Type[bounds.length];
		for (int i = 0; i < bounds.length; i++) {
			if (bounds[i] instanceof TypeVariable) {
				result[i] = resolveTypeVar((TypeVariable<?>) bounds[i], srcType, declaringClass);
			} else if (bounds[i] instanceof ParameterizedType) {
				result[i] = resolveParameterizedType((ParameterizedType) bounds[i], srcType,
					declaringClass);
			} else if (bounds[i] instanceof WildcardType) {
				result[i] = resolveWildcardType((WildcardType) bounds[i], srcType, declaringClass);
			} else {
				result[i] = bounds[i];
			}
		}
		return result;
	}

	/**
	 * 解析类中typeVar真实类型，可能出现父类声明了typeVar的泛型类型，而子类指定了具体类型，
	 * 通过typeVar定义的类型、typeVar真实类型所在的类的类型srcType，已经定义了typeVar所在类的类型找到typeVar
	 * 在srcType中的真实类型
	 *
	 * @param typeVar 需要解析真实类型的类型
	 * @param srcType 当前类的类型
	 * @param declaringClass 定义待解析typeVar所在类的类型
	 */
	private static Type resolveTypeVar(TypeVariable<?> typeVar, Type srcType,
		Class<?> declaringClass) {
		Type result = null;
		Class<?> clazz = null;
		//普通的类直接赋值
		if (srcType instanceof Class) {
			clazz = (Class<?>) srcType;
		} else if (srcType instanceof ParameterizedType) {
			//参数中带泛型的，取泛型外的真实类型，比如List<String> -->  List
			ParameterizedType parameterizedType = (ParameterizedType) srcType;
			clazz = (Class<?>) parameterizedType.getRawType();
		} else {
			throw new IllegalArgumentException(
				"The 2nd arg must be Class or ParameterizedType, but was: " + srcType.getClass());
		}
		//真实类和申明类一样，说明没有父子类
		if (clazz == declaringClass) {
			Type[] bounds = typeVar.getBounds();
			if (bounds.length > 0) {
				return bounds[0];
			}
			return Object.class;
		}
		//存在继承,获得带参数的父类类型
		Type superclass = clazz.getGenericSuperclass();
		result = scanSuperTypes(typeVar, srcType, declaringClass, clazz, superclass);
		if (result != null) {
			return result;
		}
		//存在接口实现,获得带参数的父接口
		Type[] superInterfaces = clazz.getGenericInterfaces();
		for (Type superInterface : superInterfaces) {
			result = scanSuperTypes(typeVar, srcType, declaringClass, clazz, superInterface);
			if (result != null) {
				return result;
			}
		}
		return Object.class;
	}

	/**
	 * 获取在srcType中typeVar参数的实际类型
	 *
	 * @param typeVar 需要获得参数的类型
	 * @param srcType 类的真实类型，被解析的字段/方法等真正所处的类
	 * @param declaringClass 参数/方法在定义类中的类型
	 * @param superclass 真实类srcType的带参数父类
	 */
	private static Type scanSuperTypes(TypeVariable<?> typeVar, Type srcType,
		Class<?> declaringClass, Class<?> clazz, Type superclass) {
		if (superclass instanceof ParameterizedType) {
			//父类为参数化类型，比如 Map<K,V>
			ParameterizedType parentAsType = (ParameterizedType) superclass;
			//原始类型，比如Map
			Class<?> parentAsClass = (Class<?>) parentAsType.getRawType();
			//父类上定义的泛型类型变量，比如 [K,V]
			TypeVariable<?>[] parentTypeVars = parentAsClass.getTypeParameters();
			if (srcType instanceof ParameterizedType) {
				parentAsType = translateParentTypeVars((ParameterizedType) srcType, clazz,
					parentAsType);
			}
			//确定方法/参数声明类和当前扫描到的父类相同，先看能否从父类得到typeVar的实际类型
			if (declaringClass == parentAsClass) {
				for (int i = 0; i < parentTypeVars.length; i++) {
					//父类中定义的泛型类型和要求解析的参数类型typeVar相同
					if (typeVar == parentTypeVars[i]) {
						//对应index的真实类型就是typeVar的真实类型
						return parentAsType.getActualTypeArguments()[i];
					}
				}
			}
			//当前父类parentAsClass还是声明类declaringClass的子类，说明还没有找到最顶层的父类，递归查找
			if (declaringClass.isAssignableFrom(parentAsClass)) {
				return resolveTypeVar(typeVar, parentAsType, declaringClass);
			}
		} else if (superclass instanceof Class && declaringClass
			.isAssignableFrom((Class<?>) superclass)) {
			return resolveTypeVar(typeVar, superclass, declaringClass);
		}
		return null;
	}

	private static ParameterizedType translateParentTypeVars(ParameterizedType srcType,
		Class<?> srcClass, ParameterizedType parentType) {
		Type[] parentTypeArgs = parentType.getActualTypeArguments();
		Type[] srcTypeArgs = srcType.getActualTypeArguments();
		TypeVariable<?>[] srcTypeVars = srcClass.getTypeParameters();
		Type[] newParentArgs = new Type[parentTypeArgs.length];
		boolean noChange = true;
		for (int i = 0; i < parentTypeArgs.length; i++) {
			if (parentTypeArgs[i] instanceof TypeVariable) {
				for (int j = 0; j < srcTypeVars.length; j++) {
					if (srcTypeVars[j] == parentTypeArgs[i]) {
						noChange = false;
						newParentArgs[i] = srcTypeArgs[j];
					}
				}
			} else {
				newParentArgs[i] = parentTypeArgs[i];
			}
		}
		return noChange ? parentType
			: new ParameterizedTypeImpl((Class<?>) parentType.getRawType(), null, newParentArgs);
	}

	private TypeParameterResolver() {
		super();
	}

	static class ParameterizedTypeImpl implements ParameterizedType {
		//除去泛型的原始类型
		private Class<?> rawType;

		private Type ownerType;
		//泛型对应的真实类型
		private Type[] actualTypeArguments;

		public ParameterizedTypeImpl(Class<?> rawType, Type ownerType, Type[] actualTypeArguments) {
			super();
			this.rawType = rawType;
			this.ownerType = ownerType;
			this.actualTypeArguments = actualTypeArguments;
		}

		@Override
		public Type[] getActualTypeArguments() {
			return actualTypeArguments;
		}

		@Override
		public Type getOwnerType() {
			return ownerType;
		}

		@Override
		public Type getRawType() {
			return rawType;
		}

		@Override
		public String toString() {
			return "ParameterizedTypeImpl [rawType=" + rawType + ", ownerType=" + ownerType
				+ ", actualTypeArguments=" + Arrays.toString(actualTypeArguments) + "]";
		}
	}

	static class WildcardTypeImpl implements WildcardType {

		private Type[] lowerBounds;

		private Type[] upperBounds;

		WildcardTypeImpl(Type[] lowerBounds, Type[] upperBounds) {
			super();
			this.lowerBounds = lowerBounds;
			this.upperBounds = upperBounds;
		}

		@Override
		public Type[] getLowerBounds() {
			return lowerBounds;
		}

		@Override
		public Type[] getUpperBounds() {
			return upperBounds;
		}
	}

	static class GenericArrayTypeImpl implements GenericArrayType {

		private Type genericComponentType;

		GenericArrayTypeImpl(Type genericComponentType) {
			super();
			this.genericComponentType = genericComponentType;
		}

		@Override
		public Type getGenericComponentType() {
			return genericComponentType;
		}
	}
}
