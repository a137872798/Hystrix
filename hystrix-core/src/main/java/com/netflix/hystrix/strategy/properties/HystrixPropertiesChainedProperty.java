/**
 * Copyright 2016 Netflix, Inc.
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
package com.netflix.hystrix.strategy.properties;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.netflix.hystrix.strategy.HystrixPlugins;

/**
 * Chained property allowing a chain of defaults properties which is uses the properties plugin.
 * <p>
 * Instead of just a single dynamic property with a default this allows a sequence of properties that fallback to the farthest down the chain with a value.
 * <p>
 * TODO This should be replaced by a version in the Archaius library once available.
 *
 * @ExcludeFromJavadoc hystrix 的链式属性
 */
public abstract class HystrixPropertiesChainedProperty {
    private static final Logger logger = LoggerFactory.getLogger(HystrixPropertiesChainedProperty.class);

    /**
     * 链节点对象
     *
     * @ExcludeFromJavadoc
     */
    private static abstract class ChainLink<T> {

        /**
         * 原子引用对象
         */
        private final AtomicReference<ChainLink<T>> pReference;
        /**
         * 下个节点
         */
        private final ChainLink<T> next;
        /**
         * 绑定在本节点的一系列回调对象
         */
        private final List<Runnable> callbacks;

        /**
         * 获取chain 的name
         *
         * @return String
         */
        public abstract String getName();

        /**
         * 获取chain 的value
         *
         * @return T
         */
        protected abstract T getValue();

        /**
         * 代表本节点是否存数值/否则会存储下个节点
         *
         * @return Boolean
         */
        public abstract boolean isValueAcceptable();

        /**
         * No arg constructor - used for end node
         * 初始化一个空对象
         */
        public ChainLink() {
            next = null;
            pReference = new AtomicReference<ChainLink<T>>(this);
            callbacks = new ArrayList<Runnable>();
        }

        /**
         * @param nextProperty next property in the chain
         *                     使用next 来初始化该对象
         */
        public ChainLink(ChainLink<T> nextProperty) {
            next = nextProperty;
            pReference = new AtomicReference<ChainLink<T>>(next);
            callbacks = new ArrayList<Runnable>();
        }

        /**
         * 检查 AND 反转
         */
        protected void checkAndFlip() {
            // in case this is the end node
            // 当不存在 next节点时 将本节点设置到 reference中
            if (next == null) {
                pReference.set(this);
                return;
            }

            // 代表本节点能否存数值 否则就是存下个节点的引用
            if (this.isValueAcceptable()) {
                logger.debug("Flipping property: {} to use its current value: {}", getName(), getValue());
                pReference.set(this);
            } else {
                // 将 ref 设置成下个节点
                logger.debug("Flipping property: {} to use NEXT property: {}", getName(), next);
                pReference.set(next);
            }

            // 执行回调函数
            for (Runnable r : callbacks) {
                r.run();
            }
        }

        /**
         * @return T
         */
        public T get() {
            if (pReference.get() == this) {
                return this.getValue();
            } else {
                // 代表调用下个节点的 get() 方法 如果isValueAcceptable 还是 false 就代表下个节点的 value属性存放的 是下下个节点
                return pReference.get().get();
            }
        }

        /**
         * 设置回调对象  在 checkAndFilp 中被调用
         *
         * @param r callback to execut
         */
        public void addCallback(Runnable r) {
            callbacks.add(r);
        }

        /**
         * @return String
         */
        public String toString() {
            return getName() + " = " + get();
        }
    }

    /**
     * 链构建对象
     *
     * @param <T>
     */
    public static abstract class ChainBuilder<T> {

        private ChainBuilder() {
            super();
        }

        /**
         * 一组动态属性对象
         */
        private List<HystrixDynamicProperty<T>> properties =
                new ArrayList<HystrixDynamicProperty<T>>();


        /**
         * 将元素添加到动态属性列表中
         *
         * @param property
         * @return
         */
        public ChainBuilder<T> add(HystrixDynamicProperty<T> property) {
            properties.add(property);
            return this;
        }

        /**
         * 将传入的 kv 生成 动态属性对象 并条件到属性列表中
         * @param name
         * @param defaultValue
         * @return
         */
        public ChainBuilder<T> add(String name, T defaultValue) {
            properties.add(getDynamicProperty(name, defaultValue, getType()));
            return this;
        }

        /**
         * 构建 一个动态属性对象
         * @return
         */
        public HystrixDynamicProperty<T> build() {
            // 没有设置属性的情况下 直接调用构建方法 会抛出异常
            if (properties.size() < 1) throw new IllegalArgumentException();
            // 返回第一个属性
            if (properties.size() == 1) return properties.get(0);
            // 动态属性列表的副本对象
            List<HystrixDynamicProperty<T>> reversed =
                    new ArrayList<HystrixDynamicProperty<T>>(properties);
            Collections.reverse(reversed);
            ChainProperty<T> current = null;
            // 将对象进行反转后 生成一个属性链
            for (HystrixDynamicProperty<T> p : reversed) {
                if (current == null) {
                    current = new ChainProperty<T>(p);
                } else {
                    // 将 当前节点 指向 包装了原节点的新对象
                    current = new ChainProperty<T>(p, current);
                }
            }

            // 将chain 包装成一个 prop 对象
            return new ChainHystrixProperty<T>(current);
        }

        protected abstract Class<T> getType();

    }

    /**
     * ChainBuilder 的默认实现
     * @param type
     * @param <T>
     * @return
     */
    private static <T> ChainBuilder<T> forType(final Class<T> type) {
        // getType 的实现方法就是直接返回 type
        return new ChainBuilder<T>() {
            @Override
            protected Class<T> getType() {
                return type;
            }
        };
    }

    // 返回对应的 chainBuilder 对象

    public static ChainBuilder<String> forString() {
        return forType(String.class);
    }

    public static ChainBuilder<Integer> forInteger() {
        return forType(Integer.class);
    }

    public static ChainBuilder<Boolean> forBoolean() {
        return forType(Boolean.class);
    }

    public static ChainBuilder<Long> forLong() {
        return forType(Long.class);
    }

    /**
     * 内部维护一个 chain 对象  为什么要这样封装 也就是本对象自身是没有 链表的特性的 而内部维护的prop 属性就可以是一个链表(属于chainProp类)
     * @param <T>
     */
    private static class ChainHystrixProperty<T> implements HystrixDynamicProperty<T> {
        private final ChainProperty<T> property;

        public ChainHystrixProperty(ChainProperty<T> property) {
            super();
            this.property = property;
        }

        @Override
        public String getName() {
            return property.getName();
        }

        @Override
        public T get() {
            return property.get();
        }

        /**
         * 针对该对象 调用 addCallback 也就是 为 内部维护的 prop 添加 callback
         * @param callback
         */
        @Override
        public void addCallback(Runnable callback) {
            property.addCallback(callback);
        }

    }

    /**
     * 该对象拓展了 链表节点 为每个 node 条件了一个 prop 属性
     * @param <T>
     */
    private static class ChainProperty<T> extends ChainLink<T> {

        /**
         * node 携带的属性
         */
        private final HystrixDynamicProperty<T> sProp;

        public ChainProperty(HystrixDynamicProperty<T> sProperty) {
            super();
            sProp = sProperty;
        }

        /**
         * 传入一个 next 节点 作为下个节点进行初始化
         * @param sProperty
         * @param next
         */
        public ChainProperty(HystrixDynamicProperty<T> sProperty, ChainProperty<T> next) {
            super(next); // setup next pointer

            sProp = sProperty;
            // 为属性节点 增加了一个 回调对象 用于检查和 反转对象  （该回调不属于 chain）
            sProp.addCallback(new Runnable() {
                @Override
                public void run() {
                    logger.debug("Property changed: '{} = {}'", getName(), getValue());
                    checkAndFlip();
                }
            });
            // 首次初始化就调用 checkAndFilp 核心就是给 ref 设置本节点 or next节点
            checkAndFlip();
        }

        /**
         * 是否可设置值 的实现变成了prop 中是否包含属性
         * @return
         */
        @Override
        public boolean isValueAcceptable() {
            return (sProp.get() != null);
        }

        /**
         * 获取 prop 的属性值
         */
        @Override
        protected T getValue() {
            return sProp.get();
        }

        @Override
        public String getName() {
            return sProp.getName();
        }

    }

    /**
     * 根据传入的属性生成一个 动态属性对象
     *
     * @param propName 该属性名
     * @param defaultValue 默认值
     * @param type 属性值的类型
     * @param <T>
     * @return
     */
    private static <T> HystrixDynamicProperty<T> getDynamicProperty(String propName, T defaultValue, Class<T> type) {
        HystrixDynamicProperties properties = HystrixPlugins.getInstance().getDynamicProperties();
        HystrixDynamicProperty<T> p =
                HystrixDynamicProperties.Util.getProperty(properties, propName, defaultValue, type);
        return p;
    }

}
