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

import java.util.concurrent.Callable;

import rx.functions.Action0;
import rx.functions.Func2;

import com.netflix.hystrix.strategy.HystrixPlugins;

/**
 * Wrapper around {@link Func2} that manages the {@link HystrixRequestContext} initialization and cleanup for the execution of the {@link Func2}
 * 
 * @param <T>
 *            Return type of {@link Func2}
 * 
 * @ExcludeFromJavadoc
 * 被封装用于 线程池 指定的 函数对象 (在 使用 hystrixScheduler 对象提交并执行任务时, 普通的 action 对象都会被封装成 该对象)
 */
public class HystrixContexSchedulerAction implements Action0 {

    /**
     * 内部实际维护的 action 对象
     */
    private final Action0 actual;
    /**
     * 维护了本次请求上下文的信息
     */
    private final HystrixRequestContext parentThreadState;
    /**
     * 回调函数 该对象在什么时候触发 ???
     */
    private final Callable<Void> c;

    public HystrixContexSchedulerAction(Action0 action) {
        this(HystrixPlugins.getInstance().getConcurrencyStrategy(), action);
    }

    /**
     * 默认调用的 构造函数
     * @param concurrencyStrategy
     * @param action
     */
    public HystrixContexSchedulerAction(final HystrixConcurrencyStrategy concurrencyStrategy, Action0 action) {
        this.actual = action;
        // 从当前线程中获取上下文对象
        this.parentThreadState = HystrixRequestContext.getContextForCurrentThread();

        // 如果concurrencyStrategy .wrapCallable 对象 有做额外处理 这里会被装饰 默认情况下noop
        this.c = concurrencyStrategy.wrapCallable(new Callable<Void>() {

            @Override
            public Void call() throws Exception {
                // 获取当前请求上下文
                HystrixRequestContext existingState = HystrixRequestContext.getContextForCurrentThread();
                try {
                    // set the state of this thread to that of its parent
                    // 将上下文对象修改成 之前保存的 上下文对象
                    HystrixRequestContext.setContextOnCurrentThread(parentThreadState);
                    // execute actual Action0 with the state of the parent
                    // 执行函数对象
                    actual.call();
                    return null;
                } finally {
                    // restore this thread back to its original state
                    // 还原上下文对象
                    HystrixRequestContext.setContextOnCurrentThread(existingState);
                }
            }
        });
    }

    /**
     * 直接调用该对象的 call 方法时 被 代理带了 Callable 之后在 临时更换了上下文对象后 执行包含实际call() 逻辑的 action 对象 之后 换回 上下文
     */
    @Override
    public void call() {
        try {
            c.call();
        } catch (Exception e) {
            throw new RuntimeException("Failed executing wrapped Action0", e);
        }
    }

}
