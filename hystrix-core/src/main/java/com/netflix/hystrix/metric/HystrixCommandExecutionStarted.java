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
package com.netflix.hystrix.metric;

import com.netflix.hystrix.HystrixCommandKey;
import com.netflix.hystrix.HystrixCommandProperties;
import com.netflix.hystrix.HystrixThreadPoolKey;

/**
 * Data class that get fed to event stream when a command starts executing.
 * 代表command 开始 execution 时的 事件对象
 * 每个 commandKey 和 threadPoolKey 定位到一个 event 对象
 */
public class HystrixCommandExecutionStarted extends HystrixCommandEvent {
    /**
     * 执行隔离策略  分为 信号量和线程池 隔离
     */
    private final HystrixCommandProperties.ExecutionIsolationStrategy isolationStrategy;
    /**
     * 当前并发数
     */
    private final int currentConcurrency;

    public HystrixCommandExecutionStarted(HystrixCommandKey commandKey, HystrixThreadPoolKey threadPoolKey,
                                          HystrixCommandProperties.ExecutionIsolationStrategy isolationStrategy,
                                          int currentConcurrency) {
        super(commandKey, threadPoolKey);
        this.isolationStrategy = isolationStrategy;
        this.currentConcurrency = currentConcurrency;
    }

    /**
     * 当前是否开始执行 返回true
     * @return
     */
    @Override
    public boolean isExecutionStart() {
        return true;
    }

    /**
     * 隔离策略有2种 一种是 基于线程 一种是基于 信号量
     * @return
     */
    @Override
    public boolean isExecutedInThread() {
        return isolationStrategy == HystrixCommandProperties.ExecutionIsolationStrategy.THREAD;
    }

    /**
     * 开始执行就代表没有被线程池拒绝
     * @return
     */
    @Override
    public boolean isResponseThreadPoolRejected() {
        return false;
    }

    @Override
    public boolean isCommandCompletion() {
        return false;
    }

    @Override
    public boolean didCommandExecute() {
        return false;
    }

    public int getCurrentConcurrency() {
        return currentConcurrency;
    }

}
