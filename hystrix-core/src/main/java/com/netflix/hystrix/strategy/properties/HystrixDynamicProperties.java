/**
 * Copyright 2016 Netflix, Inc.
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.hystrix.strategy.properties;

import java.util.ServiceLoader;

/**
 * A hystrix plugin (SPI) for resolving dynamic configuration properties. This
 * SPI allows for varying configuration sources.
 * 
 * The HystrixPlugin singleton will load only one implementation of this SPI
 * throught the {@link ServiceLoader} mechanism.
 * 
 * @author agentgt
 * 熔断器 动态属性 内部应该维护了多个属性
 *
 */
public interface HystrixDynamicProperties {
    
    /**
     * Requests a property that may or may not actually exist.
     * @param name property name, never <code>null</code>
     * @param fallback default value, maybe <code>null</code>
     * @return never <code>null</code>
     * 根据传入的属性名 获取对应的属性值
     */
    public HystrixDynamicProperty<String> getString(String name, String fallback);
    /**
     * Requests a property that may or may not actually exist.
     * @param name property name, never <code>null</code>
     * @param fallback default value, maybe <code>null</code>
     * @return never <code>null</code>
     * 根据传入的属性名 获取对应的属性值
     */
    public HystrixDynamicProperty<Integer> getInteger(String name, Integer fallback);
    /**
     * Requests a property that may or may not actually exist.
     * @param name property name, never <code>null</code>
     * @param fallback default value, maybe <code>null</code>
     * @return never <code>null</code>
     */
    public HystrixDynamicProperty<Long> getLong(String name, Long fallback);
    /**
     * Requests a property that may or may not actually exist.
     * @param name property name
     * @param fallback default value
     * @return never <code>null</code>
     */
    public HystrixDynamicProperty<Boolean> getBoolean(String name, Boolean fallback);
    
    /**
     * 静态单元对象
     * @ExcludeFromJavadoc
     */
    public static class Util {
        /**
         * 该方法属于静态方法
         * A convenience method to get a property by type (Class).
         * @param properties never <code>null</code> 传入的 属性对象
         * @param name never <code>null</code> 属性名
         * @param fallback maybe <code>null</code> 传入的回调对象
         * @param type never <code>null</code> 类型
         * @return a dynamic property with type T.
         * 获取属性
         */
        @SuppressWarnings("unchecked")
        public static <T> HystrixDynamicProperty<T> getProperty(
                HystrixDynamicProperties properties, String name, T fallback, Class<T> type) {
            return (HystrixDynamicProperty<T>) doProperty(properties, name, fallback, type);
        }

        /**
         * 根据要求获取的属性类型 自动进行转换
         * @param delegate
         * @param name
         * @param fallback
         * @param type
         * @return
         */
        private static HystrixDynamicProperty<?> doProperty(
                HystrixDynamicProperties delegate, 
                String name, Object fallback, Class<?> type) {
            if(type == String.class) {
                return delegate.getString(name, (String) fallback);
            }
            else if (type == Integer.class) {
                return delegate.getInteger(name, (Integer) fallback);
            }
            else if (type == Long.class) {
                return delegate.getLong(name, (Long) fallback);
            }
            else if (type == Boolean.class) {
                return delegate.getBoolean(name, (Boolean) fallback);
            }
            throw new IllegalStateException();
        }
    }
    
}