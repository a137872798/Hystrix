/**
 * Copyright 2012 Netflix, Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.hystrix.strategy.properties;

import java.util.concurrent.ConcurrentHashMap;

import com.netflix.hystrix.HystrixCollapserKey;
import com.netflix.hystrix.HystrixCollapserProperties;
import com.netflix.hystrix.HystrixCommand;
import com.netflix.hystrix.HystrixCommandKey;
import com.netflix.hystrix.HystrixCommandProperties;
import com.netflix.hystrix.HystrixThreadPool;
import com.netflix.hystrix.HystrixThreadPoolKey;
import com.netflix.hystrix.HystrixThreadPoolProperties;
import com.netflix.hystrix.strategy.HystrixPlugins;

/**
 * Factory for retrieving properties implementations.
 * <p>
 * This uses given {@link HystrixPropertiesStrategy} implementations to construct Properties instances and caches each instance according to the cache key provided.
 * 
 * @ExcludeFromJavadoc
 * hystrix 的 属性工厂
 */
public class HystrixPropertiesFactory {

    /**
     * Clears all the defaults in the static property cache. This makes it possible for property defaults to not persist for
     * an entire JVM lifetime.  May be invoked directly, and also gets invoked by <code>Hystrix.reset()</code>
     * 将 command threadPool collapser 相关的数据都清除
     */
    public static void reset() {
        commandProperties.clear();
        threadPoolProperties.clear();
        collapserProperties.clear();
    }

    // String is CommandKey.name() (we can't use CommandKey directly as we can't guarantee it implements hashcode/equals correctly)
    // 命令相关的 属性容器
    private static final ConcurrentHashMap<String, HystrixCommandProperties> commandProperties = new
            ConcurrentHashMap<String, HystrixCommandProperties>();

    /**
     * Get an instance of {@link HystrixCommandProperties} with the given factory {@link HystrixPropertiesStrategy} implementation for each {@link HystrixCommand} instance.
     * 
     * @param key
     *            Pass-thru to {@link HystrixPropertiesStrategy#getCommandProperties} implementation.
     * @param builder
     *            Pass-thru to {@link HystrixPropertiesStrategy#getCommandProperties} implementation.
     * @return {@link HystrixCommandProperties} instance
     * 获取 command 相关的属性
     */
    public static HystrixCommandProperties getCommandProperties(HystrixCommandKey key, HystrixCommandProperties.Setter builder) {
        // 获取属性策略对象 就是通过扫描 systemProp 指定的key 来找到 impl的类名 / 或者通过SPI机制
        HystrixPropertiesStrategy hystrixPropertiesStrategy = HystrixPlugins.getInstance().getPropertiesStrategy();
        // 获取缓存键的name 属性 就是直接调用 key.name()
        String cacheKey = hystrixPropertiesStrategy.getCommandPropertiesCacheKey(key, builder);
        if (cacheKey != null) {
            // 尝试从缓存中获取对象
            HystrixCommandProperties properties = commandProperties.get(cacheKey);
            if (properties != null) {
                return properties;
            } else {
                // 如果builder 为 空 先创建 builder 对象
                if (builder == null) {
                    builder = HystrixCommandProperties.Setter();
                }
                // create new instance
                // 创建实例对象 该对象就会从 builder 中获取默认属性
                properties = hystrixPropertiesStrategy.getCommandProperties(key, builder);
                // cache and return
                // 这里没有使用 synchronize 原语来进行上锁
                HystrixCommandProperties existing = commandProperties.putIfAbsent(cacheKey, properties);
                if (existing == null) {
                    return properties;
                } else {
                    return existing;
                }
            }
        } else {
            // no cacheKey so we generate it with caching
            // 没有缓存键的情况下 直接返回一个对象 该对象没有存入缓存
            return hystrixPropertiesStrategy.getCommandProperties(key, builder);
        }
    }

    // String is ThreadPoolKey.name() (we can't use ThreadPoolKey directly as we can't guarantee it implements hashcode/equals correctly)
    /**
     * 线程池相关的 容器
     */
    private static final ConcurrentHashMap<String, HystrixThreadPoolProperties> threadPoolProperties = new ConcurrentHashMap<String, HystrixThreadPoolProperties>();

    /**
     * Get an instance of {@link HystrixThreadPoolProperties} with the given factory {@link HystrixPropertiesStrategy} implementation for each {@link HystrixThreadPool} instance.
     * 
     * @param key
     *            Pass-thru to {@link HystrixPropertiesStrategy#getThreadPoolProperties} implementation.
     * @param builder
     *            Pass-thru to {@link HystrixPropertiesStrategy#getThreadPoolProperties} implementation.
     * @return {@link HystrixThreadPoolProperties} instance
     * 获取线程池相关的属性
     */
    public static HystrixThreadPoolProperties getThreadPoolProperties(HystrixThreadPoolKey key, HystrixThreadPoolProperties.Setter builder) {
        // 通过 systemProp 或者 SPI 加载
        HystrixPropertiesStrategy hystrixPropertiesStrategy = HystrixPlugins.getInstance().getPropertiesStrategy();
        // 尝试获取对应的缓存键
        String cacheKey = hystrixPropertiesStrategy.getThreadPoolPropertiesCacheKey(key, builder);
        if (cacheKey != null) {
            //  获取被缓存的属性对象
            HystrixThreadPoolProperties properties = threadPoolProperties.get(cacheKey);
            if (properties != null) {
                return properties;
            } else {
                if (builder == null) {
                    builder = HystrixThreadPoolProperties.Setter();
                }
                // create new instance  使用builder 对象去 构建 prop 默认情况下 会从builder 中获取属性
                properties = hystrixPropertiesStrategy.getThreadPoolProperties(key, builder);
                // cache and return
                HystrixThreadPoolProperties existing = threadPoolProperties.putIfAbsent(cacheKey, properties);
                if (existing == null) {
                    return properties;
                } else {
                    return existing;
                }
            }
        } else {
            // no cacheKey so we generate it with caching 这里没有保存缓存 直接返回
            return hystrixPropertiesStrategy.getThreadPoolProperties(key, builder);
        }
    }

    // 同上

    // String is CollapserKey.name() (we can't use CollapserKey directly as we can't guarantee it implements hashcode/equals correctly)
    private static final ConcurrentHashMap<String, HystrixCollapserProperties> collapserProperties = new ConcurrentHashMap<String, HystrixCollapserProperties>();

    /**
     * Get an instance of {@link HystrixCollapserProperties} with the given factory {@link HystrixPropertiesStrategy} implementation for each {@link HystrixCollapserKey} instance.
     * 
     * @param key
     *            Pass-thru to {@link HystrixPropertiesStrategy#getCollapserProperties} implementation.
     * @param builder
     *            Pass-thru to {@link HystrixPropertiesStrategy#getCollapserProperties} implementation.
     * @return {@link HystrixCollapserProperties} instance
     */
    public static HystrixCollapserProperties getCollapserProperties(HystrixCollapserKey key, HystrixCollapserProperties.Setter builder) {
        HystrixPropertiesStrategy hystrixPropertiesStrategy = HystrixPlugins.getInstance().getPropertiesStrategy();
        String cacheKey = hystrixPropertiesStrategy.getCollapserPropertiesCacheKey(key, builder);
        if (cacheKey != null) {
            HystrixCollapserProperties properties = collapserProperties.get(cacheKey);
            if (properties != null) {
                return properties;
            } else {
                if (builder == null) {
                    builder = HystrixCollapserProperties.Setter();
                }
                // create new instance
                properties = hystrixPropertiesStrategy.getCollapserProperties(key, builder);
                // cache and return
                HystrixCollapserProperties existing = collapserProperties.putIfAbsent(cacheKey, properties);
                if (existing == null) {
                    return properties;
                } else {
                    return existing;
                }
            }
        } else {
            // no cacheKey so we generate it with caching
            return hystrixPropertiesStrategy.getCollapserProperties(key, builder);
        }
    }

}
