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
package com.netflix.hystrix;

import com.netflix.hystrix.util.InternMap;

/**
 * A key to represent a {@link HystrixThreadPool} for monitoring, metrics publishing, caching and other such uses.
 * <p>
 * This interface is intended to work natively with Enums so that implementing code can be an enum that implements this interface.
 * 线程池键对象 也是实现了 Hystrix  该对象只有一个 name 属性
 */
public interface HystrixThreadPoolKey extends HystrixKey {
    class Factory {
        private Factory() {
        }

        // used to intern instances so we don't keep re-creating them millions of times for the same key
        private static final InternMap<String, HystrixThreadPoolKey> intern
                = new InternMap<String, HystrixThreadPoolKey>(
                new InternMap.ValueConstructor<String, HystrixThreadPoolKey>() {
                    /**
                     * 通过 key 来生成对应的 value
                     * @param key
                     * @return
                     */
                    @Override
                    public HystrixThreadPoolKey create(String key) {
                        return new HystrixThreadPoolKeyDefault(key);
                    }
                });

        /**
         * Retrieve (or create) an interned HystrixThreadPoolKey instance for a given name.
         * 
         * @param name thread pool name
         * @return HystrixThreadPoolKey instance that is interned (cached) so a given name will always retrieve the same instance.
         */
        public static HystrixThreadPoolKey asKey(String name) {
           return intern.interned(name);
        }

        private static class HystrixThreadPoolKeyDefault extends HystrixKeyDefault implements HystrixThreadPoolKey {
            public HystrixThreadPoolKeyDefault(String name) {
                super(name);
            }
        }

        /* package-private */ static int getThreadPoolCount() {
            return intern.size();
        }
    }
}
