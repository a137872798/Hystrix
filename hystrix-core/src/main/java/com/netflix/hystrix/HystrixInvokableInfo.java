/**
 * Copyright 2014 Netflix, Inc.
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

import java.util.List;

/**
 * 标明是 hystrix可调用对象的信息
 * @param <R>
 */
public interface HystrixInvokableInfo<R> {

    /**
     * 属于哪个命令组
     * @return
     */
    HystrixCommandGroupKey getCommandGroup();

    /**
     * 获取命令键
     * @return
     */
    HystrixCommandKey getCommandKey();

    /**
     * 获取 hystrix 的线程池 key
     * @return
     */
    HystrixThreadPoolKey getThreadPoolKey();

    /**
     * 获取 公共缓存名
     * @return
     */
    String getPublicCacheKey(); //have to use public in the name, as there's already a protected {@link AbstractCommand#getCacheKey()} method.

    /**
     * 冲突发源 key ???
     * @return
     */
    HystrixCollapserKey getOriginatingCollapserKey();

    /**
     * 获取针对 某一Command 的统计数据
     * @return
     */
    HystrixCommandMetrics getMetrics();

    /**
     * 获取命令属性
     * @return
     */
    HystrixCommandProperties getProperties();

    /**
     * 循环 breaker Open ???
     * @return
     */
    boolean isCircuitBreakerOpen();

    /**
     * 是否执行完成
     * @return
     */
    boolean isExecutionComplete();

    /**
     * 是否在线程中执行
     * @return
     */
    boolean isExecutedInThread();

    /**
     * 是否执行成功
     * @return
     */
    boolean isSuccessfulExecution();

    /**
     * 是否执行失败
     * @return
     */
    boolean isFailedExecution();

    /**
     * 获取失败原因
     * @return
     */
    Throwable getFailedExecutionException();

    /**
     * 是否是从 回退中返回的 响应结果
     * @return
     */
    boolean isResponseFromFallback();

    /**
     * 是否是因为超时返回的响应结果
     * @return
     */
    boolean isResponseTimedOut();

    /**
     * 是否是从短循环返回的响应结果w
     * @return
     */
    boolean isResponseShortCircuited();

    /**
     * 是否从缓存中获取响应结果
     * @return
     */
    boolean isResponseFromCache();

    /**
     * 是否是被拒绝状态的 响应结果
     * @return
     */
    boolean isResponseRejected();

    /**
     * 是否是被信号量拒绝
     * @return
     */
    boolean isResponseSemaphoreRejected();

    /**
     * 是否是被线程拒绝
     * @return
     */
    boolean isResponseThreadPoolRejected();

    /**
     * 获取执行的 事件
     * @return
     */
    List<HystrixEventType> getExecutionEvents();

    /**
     * 散发数???
     * @return
     */
    int getNumberEmissions();

    /**
     * 回退发散数???
     * @return
     */
    int getNumberFallbackEmissions();

    /**
     * 碰撞数
     * @return
     */
    int getNumberCollapsed();

    /**
     * 每秒执行次数
     * @return
     */
    int getExecutionTimeInMilliseconds();

    /**
     * 命令开始时间
     * @return
     */
    long getCommandRunStartTimeInNanos();

    /**
     * 获取事件次数???
     * @return
     */
    ExecutionResult.EventCounts getEventCounts();
}