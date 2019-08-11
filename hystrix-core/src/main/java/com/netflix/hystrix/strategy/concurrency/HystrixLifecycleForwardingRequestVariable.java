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
package com.netflix.hystrix.strategy.concurrency;

/**
 * Implementation of {@link HystrixRequestVariable} which forwards to the wrapped
 * {@link HystrixRequestVariableLifecycle}.
 * <p>
 * This implementation also returns null when {@link #get()} is called while the {@link HystrixRequestContext} has not
 * been initialized rather than throwing an exception, allowing for use in a {@link HystrixConcurrencyStrategy} which
 * does not depend on an a HystrixRequestContext
 */
public class HystrixLifecycleForwardingRequestVariable<T> extends HystrixRequestVariableDefault<T> {

    /**
     * 内部维护了一个 hystrix 的生命周期对象
     */
    private final HystrixRequestVariableLifecycle<T> lifecycle;

    /**
     * Creates a HystrixRequestVariable which will return data as provided by the {@link HystrixRequestVariableLifecycle}
     * @param lifecycle lifecycle used to provide values. Must have the same type parameter as the constructed instance.
     */
    public HystrixLifecycleForwardingRequestVariable(HystrixRequestVariableLifecycle<T> lifecycle) {
        this.lifecycle = lifecycle;
    }

    /**
     * Delegates to the wrapped {@link HystrixRequestVariableLifecycle}
     * @return T with initial value or null if none.
     * 针对 生命周期对象进行初始化
     */
    @Override
    public T initialValue() {
        return lifecycle.initialValue();
    }

    /**
     * Delegates to the wrapped {@link HystrixRequestVariableLifecycle}
     * @param value
     *            of request variable to allow cleanup activity.
     *            <p>
     *            If nothing needs to be cleaned up then nothing needs to be done in this method.
     *            调用 lifecycle.shutdown 方法
     */
    @Override
    public void shutdown(T value) {
        lifecycle.shutdown(value);
    }

    /**
     * Return null if the {@link HystrixRequestContext} has not been initialized for the current thread.
     * <p>
     * If {@link HystrixRequestContext} has been initialized then call method in superclass:
     * {@link HystrixRequestVariableDefault#get()}
     * 当 context 对象被初始化完毕时 通过lazyInit 对象 生成需要的值  如果 context 还不存在 返回null
     */
    @Override
    public T get() {
        if (!HystrixRequestContext.isCurrentThreadInitialized()) {
            return null;
        }
        return super.get();
    }

}
