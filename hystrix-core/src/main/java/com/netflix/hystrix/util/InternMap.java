package com.netflix.hystrix.util;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Utility to have 'intern' - like functionality, which holds single instance of wrapper for a given key
 * 内部容器对象
 */
public class InternMap<K, V> {
    /**
     * 实际进行存储的容器对象
     */
    private final ConcurrentMap<K, V> storage = new ConcurrentHashMap<K, V>();
    private final ValueConstructor<K, V> valueConstructor;

    /**
     * 具备 根据 k生成 v 的 构造器对象
     * @param <K>
     * @param <V>
     */
    public interface ValueConstructor<K, V> {

        /**
         * 通过 key 来生成 value
         * @param key
         * @return
         */
        V create(K key);
    }

    public InternMap(ValueConstructor<K, V> valueConstructor) {
        this.valueConstructor = valueConstructor;
    }

    /**
     * 禁锢 ???
     * @param key
     * @return
     */
    public V interned(K key) {
        // 从容器中获取 value 的值
        V existingKey = storage.get(key);
        V newKey = null;
        if (existingKey == null) {
            // value 不存在的情况下 就生成一个新的 value
            newKey = valueConstructor.create(key);
            // 存入时 判断是否发生并发情况
            existingKey = storage.putIfAbsent(key, newKey);
        }
        // 如果已经被设置了结果就是用之前的结果
        return existingKey != null ? existingKey : newKey;
    }

    public int size() {
        return storage.size();
    }
}
