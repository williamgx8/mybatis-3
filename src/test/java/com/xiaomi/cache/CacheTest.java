package com.xiaomi.cache;

import static org.junit.Assert.assertEquals;

import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import org.junit.Test;

public class CacheTest {

	/**
	 * 插入的方向是队尾，出队列、删除的方向是队头
	 */

	@Test
	public void accessOrderTest() {
		Map<Integer, String> map
			= new LinkedHashMap<>(16, .75f, true);
		map.put(1, "1");
		map.put(2, "2");
		map.put(3, "3");
		map.put(4, "4");
		map.put(5, "5");

		Set<Integer> keys = map.keySet();
		Collection<String> values = map.values();
		//   队尾 ---> 队头
		assertEquals("[1, 2, 3, 4, 5]", keys.toString());
		System.out.println(values);

		map.get(4);
		assertEquals("[1, 2, 3, 5, 4]", keys.toString());
		System.out.println(values);

		map.get(1);
		assertEquals("[2, 3, 5, 4, 1]", keys.toString());
		System.out.println(values);

		map.get(3);
		assertEquals("[2, 5, 4, 1, 3]", keys.toString());
		System.out.println(values);
	}

	@Test
	public void insertOrderTest() {
		Map<Integer, String> map = new LinkedHashMap<>(16, .75f, false);
		map.put(1, "1");
		map.put(2, "2");
		map.put(3, "3");

		Set<Integer> set = map.keySet();
		System.out.println(set);
		iterate(map);

		map.get(1);

		System.out.println(set);
		iterate(map);

	}

	private void iterate(Map<Integer, String> map) {
		Iterator<Entry<Integer, String>> iterator = map.entrySet().iterator();
		while (iterator.hasNext()) {
			System.out.println(iterator.next());
		}
	}

}
