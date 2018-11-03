/**
 * Copyright 2009-2016 the original author or authors.
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
package org.apache.ibatis.type;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

/**
 * References a generic type.
 * 泛型引用
 *
 * @param <T> the referenced type
 * @author Simone Tripodi
 * @since 3.1.0
 */
public abstract class TypeReference<T> {

    //泛型的真实类型
    private final Type rawType;

    protected TypeReference() {
        /**
         * 由于该类肯定是要被继承的，那么getClass返回的就是具体的子类类型，
         * 不管继承关系怎样，肯定可以通过当前类往上寻找，最终得到泛型的真实类型
         */
        rawType = getSuperclassTypeParameter(getClass());
    }

    Type getSuperclassTypeParameter(Class<?> clazz) {
        //得到带泛型的父类类型
        Type genericSuperclass = clazz.getGenericSuperclass();
        //type为Class说明该父类不带泛型，继续向上找
        if (genericSuperclass instanceof Class) {
            // try to climb up the hierarchy until meet something useful
            //如果已经找到TypeReference还没找到，说明出问题了
            if (TypeReference.class != genericSuperclass) {
                //递归
                return getSuperclassTypeParameter(clazz.getSuperclass());
            }

            throw new TypeException("'" + getClass() + "' extends TypeReference but misses the type parameter. "
                    + "Remove the extension or add a type parameter to it.");
        }

        //存在泛型，得到真实类型
        Type rawType = ((ParameterizedType) genericSuperclass).getActualTypeArguments()[0];
        // TODO remove this when Reflector is fixed to return Types
        //泛型内部还有泛型，再找一层
        if (rawType instanceof ParameterizedType) {
            rawType = ((ParameterizedType) rawType).getRawType();
        }

        return rawType;
    }

    public final Type getRawType() {
        return rawType;
    }

    @Override
    public String toString() {
        return rawType.toString();
    }

}
