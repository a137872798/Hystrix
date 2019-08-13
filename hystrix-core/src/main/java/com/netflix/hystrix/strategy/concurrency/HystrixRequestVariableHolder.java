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
package com.netflix.hystrix.strategy.concurrency;

import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory that encompasses functionality of {@link HystrixRequestVariable} for internal Hystrix code.
 * <p>
 * This is used as a layer between the actual {@link HystrixRequestVariable} and calling code to allow injected implementations of {@link HystrixConcurrencyStrategy}.
 * <p>
 * Typically a {@link HystrixRequestVariable} would be statically referenced (similar to a ThreadLocal) but to allow dynamic injection we instead statically reference this class which can then
 * dynamically fetch the correct implementation and statically retain an instance across threads within a context (such as {@link HystrixRequestContext}.
 * 
 * @param <T>
 * 
 * @ExcludeFromJavadoc
 * 请求变量包装器
 */
public class HystrixRequestVariableHolder<T> {

    static final Logger logger = LoggerFactory.getLogger(HystrixRequestVariableHolder.class);

    /**
     * key 对象本身内部也维护了一个 RV 对象  HystrixRequestVariable 对象内部就一个get 方法
     */
    private static ConcurrentHashMap<RVCacheKey, HystrixRequestVariable<?>> requestVariableInstance = new ConcurrentHashMap<RVCacheKey, HystrixRequestVariable<?>>();

    /**
     * 生命周期对象
     */
    private final HystrixRequestVariableLifecycle<T> lifeCycleMethods;

    /**
     * 通过传入一个 请求变量的生命周期对象进行初始化  该接口包含一个 init 和一个 shutdown 方法
     * @param lifeCycleMethods
     */
    public HystrixRequestVariableHolder(HystrixRequestVariableLifecycle<T> lifeCycleMethods) {
        this.lifeCycleMethods = lifeCycleMethods;
    }

    /**
     * 通过传入的 并发策略 获取 缓存请求变量
     * @param concurrencyStrategy
     * @return
     */
    @SuppressWarnings("unchecked")
    public T get(HystrixConcurrencyStrategy concurrencyStrategy) {
        /*
         * 1) Fetch RequestVariable implementation from cache.
         * 2) If no implementation is found in cache then construct from factory.
         * 3) Cache implementation from factory as each object instance needs to be statically cached to be relevant across threads.
         */
        // 从缓存中 获取 没有的话就 创建一个新的 并设置到缓存中
        RVCacheKey key = new RVCacheKey(this, concurrencyStrategy);
        HystrixRequestVariable<?> rvInstance = requestVariableInstance.get(key);
        if (rvInstance == null) {
            requestVariableInstance.putIfAbsent(key, concurrencyStrategy.getRequestVariable(lifeCycleMethods));
            /*
             * A safety check to help debug problems if someone starts injecting dynamically created HystrixConcurrencyStrategy instances - which should not be done and has no good reason to be done.
             * 
             * The 100 value is arbitrary ... just a number far higher than we should see.
             */
            if (requestVariableInstance.size() > 100) {
                logger.warn("Over 100 instances of HystrixRequestVariable are being stored. This is likely the sign of a memory leak caused by using unique instances of HystrixConcurrencyStrategy instead of a single instance.");
            }
        }

        // 实际 也是委托给 RV 对象 该对象 只是封装了一层 缓存的概念 (内部的 static 缓存容器 以及 cacheKey)
        return (T) requestVariableInstance.get(key).get();
    }

    /**
     * 请求变量缓存键
     */
    private static class RVCacheKey {

        /**
         * 缓存键本身就是由 RVH 对象来创建的
         */
        private final HystrixRequestVariableHolder<?> rvHolder;
        private final HystrixConcurrencyStrategy concurrencyStrategy;

        private RVCacheKey(HystrixRequestVariableHolder<?> rvHolder, HystrixConcurrencyStrategy concurrencyStrategy) {
            this.rvHolder = rvHolder;
            this.concurrencyStrategy = concurrencyStrategy;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((concurrencyStrategy == null) ? 0 : concurrencyStrategy.hashCode());
            result = prime * result + ((rvHolder == null) ? 0 : rvHolder.hashCode());
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            RVCacheKey other = (RVCacheKey) obj;
            if (concurrencyStrategy == null) {
                if (other.concurrencyStrategy != null)
                    return false;
            } else if (!concurrencyStrategy.equals(other.concurrencyStrategy))
                return false;
            if (rvHolder == null) {
                if (other.rvHolder != null)
                    return false;
            } else if (!rvHolder.equals(other.rvHolder))
                return false;
            return true;
        }

    }
}
