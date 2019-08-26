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
package com.netflix.hystrix;

import com.netflix.hystrix.util.HystrixRollingNumberEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Various states/events that execution can result in or have tracked.
 * <p>
 * These are most often accessed via {@link HystrixRequestLog} or {@link HystrixCommand#getExecutionEvents()}.
 * 熔断事件类型
 */
public enum HystrixEventType {
    /**
     * 发射事件
     */
    EMIT(false),
    /**
     * 成功
     */
    SUCCESS(true),
    /**
     * 调用失败
     */
    FAILURE(false),
    /**
     * 调用超时
     */
    TIMEOUT(false),
    /**
     * 请求失败
     */
    BAD_REQUEST(true),
    /**
     * 被短路
     */
    SHORT_CIRCUITED(false),
    /**
     * 被线程池拒绝
     */
    THREAD_POOL_REJECTED(false),
    /**
     * 被信号量拒绝
     */
    SEMAPHORE_REJECTED(false),
    /**
     * 降级发射
     */
    FALLBACK_EMIT(false),
    /**
     * 降级引发的成功
     */
    FALLBACK_SUCCESS(true),
    /**
     * 降级引发的失败
     */
    FALLBACK_FAILURE(true),
    /**
     * 降级引发的拒绝
     */
    FALLBACK_REJECTION(true),
    /**
     * 降级引发的 不能使用
     */
    FALLBACK_DISABLED(true),
    /**
     * 降级引发的 miss???
     */
    FALLBACK_MISSING(true),
    /**
     * 抛出异常
     */
    EXCEPTION_THROWN(false),
    /**
     * 从缓存中获取响应结果
     */
    RESPONSE_FROM_CACHE(true),
    /**
     * 被关闭
     */
    CANCELLED(true),
    /**
     * 发生碰撞
     */
    COLLAPSED(false),
    /**
     * 最大command 活跃 ???
     */
    COMMAND_MAX_ACTIVE(false);

    /**
     * 是否终止
     */
    private final boolean isTerminal;

    HystrixEventType(boolean isTerminal) {
        this.isTerminal = isTerminal;
    }

    public boolean isTerminal() {
        return isTerminal;
    }

    public static HystrixEventType from(HystrixRollingNumberEvent event) {
        switch (event) {
            case EMIT: return EMIT;
            case SUCCESS: return SUCCESS;
            case FAILURE: return FAILURE;
            case TIMEOUT: return TIMEOUT;
            case SHORT_CIRCUITED: return SHORT_CIRCUITED;
            case THREAD_POOL_REJECTED: return THREAD_POOL_REJECTED;
            case SEMAPHORE_REJECTED: return SEMAPHORE_REJECTED;
            case FALLBACK_EMIT: return FALLBACK_EMIT;
            case FALLBACK_SUCCESS: return FALLBACK_SUCCESS;
            case FALLBACK_FAILURE: return FALLBACK_FAILURE;
            case FALLBACK_REJECTION: return FALLBACK_REJECTION;
            case FALLBACK_DISABLED: return FALLBACK_DISABLED;
            case FALLBACK_MISSING: return FALLBACK_MISSING;
            case EXCEPTION_THROWN: return EXCEPTION_THROWN;
            case RESPONSE_FROM_CACHE: return RESPONSE_FROM_CACHE;
            case COLLAPSED: return COLLAPSED;
            case BAD_REQUEST: return BAD_REQUEST;
            case COMMAND_MAX_ACTIVE: return COMMAND_MAX_ACTIVE;
            default:
                throw new RuntimeException("Not an event that can be converted to HystrixEventType : " + event);
        }
    }

    /**
     * List of events that throw an Exception to the caller
     * 异常产生的事件类型
     */
    public final static List<HystrixEventType> EXCEPTION_PRODUCING_EVENT_TYPES = new ArrayList<HystrixEventType>();

    /**
     * List of events that are terminal
     * 终止产生的事件类型
     */
    public final static List<HystrixEventType> TERMINAL_EVENT_TYPES = new ArrayList<HystrixEventType>();

    static {
        // bad_request
        EXCEPTION_PRODUCING_EVENT_TYPES.add(BAD_REQUEST);

        // fallback_xxx

        EXCEPTION_PRODUCING_EVENT_TYPES.add(FALLBACK_FAILURE);
        EXCEPTION_PRODUCING_EVENT_TYPES.add(FALLBACK_DISABLED);
        EXCEPTION_PRODUCING_EVENT_TYPES.add(FALLBACK_MISSING);
        EXCEPTION_PRODUCING_EVENT_TYPES.add(FALLBACK_REJECTION);

        for (HystrixEventType eventType: HystrixEventType.values()) {
            if (eventType.isTerminal()) {
                TERMINAL_EVENT_TYPES.add(eventType);
            }
        }
    }

    /**
     * 线程池枚举
     */
    public enum ThreadPool {
        // 被执行还是被拒绝
        EXECUTED, REJECTED;

        public static ThreadPool from(HystrixRollingNumberEvent event) {
            switch (event) {
                case THREAD_EXECUTION: return EXECUTED;
                case THREAD_POOL_REJECTED: return REJECTED;
                default:
                    throw new RuntimeException("Not an event that can be converted to HystrixEventType.ThreadPool : " + event);
            }
        }

        public static ThreadPool from(HystrixEventType eventType) {
            switch (eventType) {
                case SUCCESS: return EXECUTED;
                case FAILURE: return EXECUTED;
                case TIMEOUT: return EXECUTED;
                case BAD_REQUEST: return EXECUTED;
                case THREAD_POOL_REJECTED: return REJECTED;
                default: return null;
            }
        }
    }

    /**
     * 碰撞枚举 ???
     */
    public enum Collapser {
        BATCH_EXECUTED, ADDED_TO_BATCH, RESPONSE_FROM_CACHE;

        public static Collapser from(HystrixRollingNumberEvent event) {
            switch (event) {
                case COLLAPSER_BATCH: return BATCH_EXECUTED;
                case COLLAPSER_REQUEST_BATCHED: return ADDED_TO_BATCH;
                case RESPONSE_FROM_CACHE: return RESPONSE_FROM_CACHE;
                default:
                    throw new RuntimeException("Not an event that can be converted to HystrixEventType.Collapser : " + event);
            }
        }
    }
}
