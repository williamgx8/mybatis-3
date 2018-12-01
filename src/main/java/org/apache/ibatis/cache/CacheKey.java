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
package org.apache.ibatis.cache;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import org.apache.ibatis.reflection.ArrayUtil;

/**
 * @author Clinton Begin
 * 根据多个值计算出来的一些数值，用这些数值综合判断多个缓存是否是同一个
 */
public class CacheKey implements Cloneable, Serializable {

	private static final long serialVersionUID = 1146682552656046210L;
	//空缓存key对象
	public static final CacheKey NULL_CACHE_KEY = new NullCacheKey();

	private static final int DEFAULT_MULTIPLYER = 37;
	private static final int DEFAULT_HASHCODE = 17;

	//系数
	private final int multiplier;
	private int hashcode;
	//校验和
	private long checksum;
	//参数个数
	private int count;
	// 8/21/2017 - Sonarlint flags this as needing to be marked transient.  While true if content is not serializable, this is not always true and thus should not be marked transient.
	private List<Object> updateList;

	public CacheKey() {
		this.hashcode = DEFAULT_HASHCODE;
		this.multiplier = DEFAULT_MULTIPLYER;
		this.count = 0;
		this.updateList = new ArrayList<>();
	}

	/**
	 * 根据一组参数计算从而生成一个CacheKey实例
	 */
	public CacheKey(Object[] objects) {
		this();
		//计算关键值
		updateAll(objects);
	}

	public int getUpdateCount() {
		return updateList.size();
	}

	public void update(Object object) {
		//hashcode基数
		int baseHashCode = object == null ? 1 : ArrayUtil.hashCode(object);

		//参数数量加一
		count++;
		//累加每个参数的hash到校验和中
		checksum += baseHashCode;
		baseHashCode *= count;

		//hashcode最终值
		hashcode = multiplier * hashcode + baseHashCode;
		//保存每一个参数对象
		updateList.add(object);
	}

	public void updateAll(Object[] objects) {
		for (Object o : objects) {
			update(o);
		}
	}

	@Override
	public boolean equals(Object object) {
		if (this == object) {
			return true;
		}
		if (!(object instanceof CacheKey)) {
			return false;
		}

		final CacheKey cacheKey = (CacheKey) object;

		/**
		 * 满足四个条件两个cacheKey才相同：
		 * 1. hashcode相同
		 * 2. 校验和相同
		 * 3. 参数数量相同
		 * 4. 每一个参数都相同
		 */
		if (hashcode != cacheKey.hashcode) {
			return false;
		}
		if (checksum != cacheKey.checksum) {
			return false;
		}
		if (count != cacheKey.count) {
			return false;
		}

		for (int i = 0; i < updateList.size(); i++) {
			Object thisObject = updateList.get(i);
			Object thatObject = cacheKey.updateList.get(i);
			if (!ArrayUtil.equals(thisObject, thatObject)) {
				return false;
			}
		}
		return true;
	}

	@Override
	public int hashCode() {
		return hashcode;
	}

	@Override
	public String toString() {
		StringBuilder returnValue = new StringBuilder().append(hashcode).append(':')
			.append(checksum);
		for (Object object : updateList) {
			returnValue.append(':').append(ArrayUtil.toString(object));
		}
		return returnValue.toString();
	}

	@Override
	public CacheKey clone() throws CloneNotSupportedException {
		CacheKey clonedCacheKey = (CacheKey) super.clone();
		clonedCacheKey.updateList = new ArrayList<>(updateList);
		return clonedCacheKey;
	}

}
