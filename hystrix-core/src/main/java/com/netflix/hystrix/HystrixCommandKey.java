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
 * A key to represent a {@link HystrixCommand} for monitoring, circuit-breakers, metrics publishing, caching and other such uses.
 * <p>
 * This interface is intended to work natively with Enums so that implementing code can be an enum that implements this interface.
 * 该接口 拓展了 hystrxiKey (原本只包含 name 的相关方法)
 */
public interface HystrixCommandKey extends HystrixKey {

    /**
     * 内部存在一个工厂类
     */
    class Factory {
        private Factory() {
        }

        // used to intern instances so we don't keep re-creating them millions of times for the same key
        /**
         * 内部类实现了 通过key 来获取 value 的方法
         */
        private static final InternMap<String, HystrixCommandKeyDefault> intern
                = new InternMap<String, HystrixCommandKeyDefault>(
                new InternMap.ValueConstructor<String, HystrixCommandKeyDefault>() {
                    @Override
                    public HystrixCommandKeyDefault create(String key) {
                        // 通过key 来生成一个 对应的Value(HystrixCommandKeyDefault)
                        return new HystrixCommandKeyDefault(key);
                    }
                });


        /**
         * Retrieve (or create) an interned HystrixCommandKey instance for a given name.
         * 
         * @param name command name
         * @return HystrixCommandKey instance that is interned (cached) so a given name will always retrieve the same instance.
         * 通过key 来查询 value 如果缓存中不存在 就会重新构建一个该对象
         */
        public static HystrixCommandKey asKey(String name) {
            return intern.interned(name);
        }

        /**
         * 默认的 hystrixCommandKey 是继承自 HystrixKeyDefault  就是内部维护了一个 name 的普通对象
         */
        private static class HystrixCommandKeyDefault extends HystrixKey.HystrixKeyDefault implements HystrixCommandKey {
            public HystrixCommandKeyDefault(String name) {
                super(name);
            }
        }

        /** 获取命令数 看来 一个 HystrixCommandKey 就被看作是一个命令 */
        static int getCommandCount() {
            return intern.size();
        }
    }

}
