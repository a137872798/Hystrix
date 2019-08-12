/**
 * Copyright 2012 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.hystrix.strategy.properties;

/**
 * Default implementation of {@link HystrixPropertiesStrategy}.
 * HystrixPropertiesStrategy 的默认实现 那么 为什么 要使用抽象类 而不是使用接口 + default 呢 也没有使用 接口 + 骨架类的方式
 * @ExcludeFromJavadoc
 */
public class HystrixPropertiesStrategyDefault extends HystrixPropertiesStrategy {

    private final static HystrixPropertiesStrategyDefault INSTANCE = new HystrixPropertiesStrategyDefault();

    private HystrixPropertiesStrategyDefault() {
    }

    public static HystrixPropertiesStrategy getInstance() {
        return INSTANCE;
    }

}
