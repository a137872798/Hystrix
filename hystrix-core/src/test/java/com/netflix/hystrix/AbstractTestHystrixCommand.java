/**
 * Copyright 2015 Netflix, Inc.
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
package com.netflix.hystrix;

import com.netflix.hystrix.strategy.properties.HystrixPropertiesStrategy;

/**
 * 执行结果对象
 * @param <R>
 */
public interface AbstractTestHystrixCommand<R> extends HystrixObservable<R>, InspectableBuilder {

    enum ExecutionResult {
        /**
         * 执行成功
         */
        SUCCESS,
        /**
         * 失败
         */
        FAILURE,
        /**
         * 异步失败
         */
        ASYNC_FAILURE,
        /**
         * 熔断失败
         */
        HYSTRIX_FAILURE,
        /**
         * 未包装失败
         */
        NOT_WRAPPED_FAILURE,
        /**
         * 异步熔断失败
         */
        ASYNC_HYSTRIX_FAILURE,
        /**
         * 恢复错误
         */
        RECOVERABLE_ERROR,
        /**
         * 异步恢复错误
         */
        ASYNC_RECOVERABLE_ERROR,
        /**
         * 非恢复错误
         */
        UNRECOVERABLE_ERROR,
        /**
         * 异步非恢复错误
         */
        ASYNC_UNRECOVERABLE_ERROR,
        /**
         * 坏请求
         */
        BAD_REQUEST,
        /**
         * 异步坏请求
         */
        ASYNC_BAD_REQUEST,
        /**
         * 未包装的坏请求
         */
        BAD_REQUEST_NOT_WRAPPED,
        /**
         * 多重发出之后成功 ???
         */
        MULTIPLE_EMITS_THEN_SUCCESS,
        /**
         * 多重发出之后失败 ???
         */
        MULTIPLE_EMITS_THEN_FAILURE,
        /**
         * 未发出 之后成功
         */
        NO_EMITS_THEN_SUCCESS
    }

    /**
     * 降级结果
     */
    enum FallbackResult {
        /**
         * 未实现???
         */
        UNIMPLEMENTED,
        /**
         * 成功
         */
        SUCCESS,
        /**
         * 失败
         */
        FAILURE,
        /**
         * 异步失败
         */
        ASYNC_FAILURE,
        /**
         * 多重发出后成功???
         */
        MULTIPLE_EMITS_THEN_SUCCESS,
        /**
         * 多重发出后失败???
         */
        MULTIPLE_EMITS_THEN_FAILURE,
        /**
         * 没有发出???
         */
        NO_EMITS_THEN_SUCCESS
    }

    /**
     * 能否被缓存
     */
    enum CacheEnabled {
        YES, NO
    }

    HystrixPropertiesStrategy TEST_PROPERTIES_FACTORY = new TestPropertiesFactory();

    class TestPropertiesFactory extends HystrixPropertiesStrategy {

        @Override
        public HystrixCommandProperties getCommandProperties(HystrixCommandKey commandKey, HystrixCommandProperties.Setter builder) {
            if (builder == null) {
                builder = HystrixCommandPropertiesTest.getUnitTestPropertiesSetter();
            }
            return HystrixCommandPropertiesTest.asMock(builder);
        }

        @Override
        public HystrixThreadPoolProperties getThreadPoolProperties(HystrixThreadPoolKey threadPoolKey, HystrixThreadPoolProperties.Setter builder) {
            if (builder == null) {
                builder = HystrixThreadPoolPropertiesTest.getUnitTestPropertiesBuilder();
            }
            return HystrixThreadPoolPropertiesTest.asMock(builder);
        }

        @Override
        public HystrixCollapserProperties getCollapserProperties(HystrixCollapserKey collapserKey, HystrixCollapserProperties.Setter builder) {
            throw new IllegalStateException("not expecting collapser properties");
        }

        @Override
        public String getCommandPropertiesCacheKey(HystrixCommandKey commandKey, HystrixCommandProperties.Setter builder) {
            return null;
        }

        @Override
        public String getThreadPoolPropertiesCacheKey(HystrixThreadPoolKey threadPoolKey, com.netflix.hystrix.HystrixThreadPoolProperties.Setter builder) {
            return null;
        }

        @Override
        public String getCollapserPropertiesCacheKey(HystrixCollapserKey collapserKey, com.netflix.hystrix.HystrixCollapserProperties.Setter builder) {
            return null;
        }

    }
}
