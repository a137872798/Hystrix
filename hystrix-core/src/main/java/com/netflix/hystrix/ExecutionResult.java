/**
 * Copyright 2016 Netflix, Inc.
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

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * Immutable holder class for the status of command execution.
 * <p>
 * This object can be referenced and "modified" by parent and child threads as well as by different instances of HystrixCommand since
 * 1 instance could create an ExecutionResult, cache a Future that refers to it, a 2nd instance execution then retrieves a Future
 * from cache and wants to append RESPONSE_FROM_CACHE to whatever the ExecutionResult was from the first command execution.
 * <p>
 * This being immutable forces and ensure thread-safety instead of using AtomicInteger/ConcurrentLinkedQueue and determining
 * when it's safe to mutate the object directly versus needing to deep-copy clone to a new instance.
 * hystrix 的执行结果对象  内部维护了 各种执行时相关的 参数 或者异常对象
 */
public class ExecutionResult {
    /**
     * 事件计数器  内部维护了 事件 中 collapser emit 等 发生的次数
     */
    private final EventCounts eventCounts;
    /**
     * 失败后的异常
     */
    private final Exception failedExecutionException;
    /**
     * 执行时出现的异常
     */
    private final Exception executionException;
    /**
     * 起始时间戳
     */
    private final long startTimestamp;
    /**
     * 执行的延迟时间  代表 执行下次 run 的 延迟时间
     */
    private final int executionLatency; //time spent in run() method
    /**
     * 用户线程延迟  代表从提交任务开始 到任务结束的时长
     */
    private final int userThreadLatency; //time elapsed between caller thread submitting request and response being visible to it
    /**
     * 是否开始执行
     */
    private final boolean executionOccurred;
    /**
     * 是否在线程中被执行
     */
    private final boolean isExecutedInThread;
    /**
     * 碰撞key
     */
    private final HystrixCollapserKey collapserKey;

    /**
     * 这里存放了所有的事件类型
     */
    private static final HystrixEventType[] ALL_EVENT_TYPES = HystrixEventType.values();
    /**
     * 所有事件的长度
     */
    private static final int NUM_EVENT_TYPES = ALL_EVENT_TYPES.length;
    /**
     * 用于存放 产生 exception 的事件对象
     */
    private static final BitSet EXCEPTION_PRODUCING_EVENTS = new BitSet(NUM_EVENT_TYPES);
    /**
     * 产生终止事件的对象
     */
    private static final BitSet TERMINAL_EVENTS = new BitSet(NUM_EVENT_TYPES);

    /**
     * 初始化时  将 exception 和 terminal 容器填充
     */
    static {
        for (HystrixEventType eventType: HystrixEventType.EXCEPTION_PRODUCING_EVENT_TYPES) {
            EXCEPTION_PRODUCING_EVENTS.set(eventType.ordinal());
        }

        for (HystrixEventType eventType: HystrixEventType.TERMINAL_EVENT_TYPES) {
            TERMINAL_EVENTS.set(eventType.ordinal());
        }
    }

    /**
     * 事件计数器
     */
    public static class EventCounts {
        /**
         * 一个 set 对象 每个元素都是一个 bit
         */
        private final BitSet events;
        /**
         * 在 hystrix 中存在几种类型的事件 包含 发射事件  和 回退发射事件  这里就是记录了对应的事件
         */
        private final int numEmissions;
        /**
         * 回退数量
         */
        private final int numFallbackEmissions;
        /**
         * 碰撞数量
         */
        private final int numCollapsed;

        EventCounts() {
            // 这里就存放了 所有的 hystrixEventType
            this.events = new BitSet(NUM_EVENT_TYPES);
            // 默认发行数量为0
            this.numEmissions = 0;
            // 默认回退数量为0
            this.numFallbackEmissions = 0;
            // 碰撞数为0
            this.numCollapsed = 0;
        }

        /**
         * 通过指定 事件对象 (位图) 次数 来创建
         * @param events
         * @param numEmissions
         * @param numFallbackEmissions
         * @param numCollapsed
         */
        EventCounts(BitSet events, int numEmissions, int numFallbackEmissions, int numCollapsed) {
            this.events = events;
            this.numEmissions = numEmissions;
            this.numFallbackEmissions = numFallbackEmissions;
            this.numCollapsed = numCollapsed;
        }

        /**
         * 通过枚举事件类初始化  应该是在执行过程中产生的一系列 事件 用来初始化该对象
         * @param eventTypes
         */
        EventCounts(HystrixEventType... eventTypes) {
            // 这里长度固定了 总是使用 HystrixEventType
            BitSet newBitSet = new BitSet(NUM_EVENT_TYPES);
            int localNumEmits = 0;
            int localNumFallbackEmits = 0;
            int localNumCollapsed = 0;
            for (HystrixEventType eventType: eventTypes) {
                switch (eventType) {
                    // 如果是 发出 给对应的  位 设置对象 并增加统计的 emit 数
                    case EMIT:
                        newBitSet.set(HystrixEventType.EMIT.ordinal());
                        localNumEmits++;
                        break;
                    // 回退时 发出 的事件
                    case FALLBACK_EMIT:
                        newBitSet.set(HystrixEventType.FALLBACK_EMIT.ordinal());
                        localNumFallbackEmits++;
                        break;
                    // 如果是碰撞事件
                    case COLLAPSED:
                        newBitSet.set(HystrixEventType.COLLAPSED.ordinal());
                        localNumCollapsed++;
                        break;
                    // 其他情况就按照该枚举的 顺序来设置对应的位图
                    default:
                        newBitSet.set(eventType.ordinal());
                        break;
                }
            }
            this.events = newBitSet;
            this.numEmissions = localNumEmits;
            this.numFallbackEmissions = localNumFallbackEmits;
            this.numCollapsed = localNumCollapsed;
        }

        /**
         * 传入 指定事件 应该是将 数据添加到当前 EventCount 中
         * @param eventType
         * @return
         */
        EventCounts plus(HystrixEventType eventType) {
            return plus(eventType, 1);
        }

        /**
         * 将 传入的 事件对象数据 与当前数据 累加  增加的 值 由 count 决定 一般就是1
         * @param eventType
         * @param count
         * @return
         */
        EventCounts plus(HystrixEventType eventType, int count) {
            // 拷贝当前 events 副本对象
            BitSet newBitSet = (BitSet) events.clone();

            // 计算 emit/fallbackEmit/collapse 事件数量
            int localNumEmits = numEmissions;
            int localNumFallbackEmits =  numFallbackEmissions;
            int localNumCollapsed = numCollapsed;
            switch (eventType) {
                case EMIT:
                    newBitSet.set(HystrixEventType.EMIT.ordinal());
                    localNumEmits += count;
                    break;
                case FALLBACK_EMIT:
                    newBitSet.set(HystrixEventType.FALLBACK_EMIT.ordinal());
                    localNumFallbackEmits += count;
                    break;
                case COLLAPSED:
                    newBitSet.set(HystrixEventType.COLLAPSED.ordinal());
                    localNumCollapsed += count;
                    break;
                default:
                    newBitSet.set(eventType.ordinal());
                    break;
            }
            // 返回一个全新对象
            return new EventCounts(newBitSet, localNumEmits, localNumFallbackEmits, localNumCollapsed);
        }

        /**
         * 判断 位图中是否存在该对象
         * @param eventType
         * @return
         */
        public boolean contains(HystrixEventType eventType) {
            return events.get(eventType.ordinal());
        }

        /**
         * 代表 只要2个 位图对象 有任何一位是 一样的
         * @param other
         * @return
         */
        public boolean containsAnyOf(BitSet other) {
            return events.intersects(other);
        }

        /**
         * 根据 事件类型 获取对应的计数
         * @param eventType
         * @return
         */
        public int getCount(HystrixEventType eventType) {
            switch (eventType) {
                case EMIT: return numEmissions;
                case FALLBACK_EMIT: return numFallbackEmissions;
                case EXCEPTION_THROWN: return containsAnyOf(EXCEPTION_PRODUCING_EVENTS) ? 1 : 0;
                case COLLAPSED: return numCollapsed;
                default: return contains(eventType) ? 1 : 0;
            }
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            EventCounts that = (EventCounts) o;

            if (numEmissions != that.numEmissions) return false;
            if (numFallbackEmissions != that.numFallbackEmissions) return false;
            if (numCollapsed != that.numCollapsed) return false;
            return events.equals(that.events);

        }

        @Override
        public int hashCode() {
            int result = events.hashCode();
            result = 31 * result + numEmissions;
            result = 31 * result + numFallbackEmissions;
            result = 31 * result + numCollapsed;
            return result;
        }

        @Override
        public String toString() {
            return "EventCounts{" +
                    "events=" + events +
                    ", numEmissions=" + numEmissions +
                    ", numFallbackEmissions=" + numFallbackEmissions +
                    ", numCollapsed=" + numCollapsed +
                    '}';
        }
    }

    /**
     * 就是传入 各个 成员变量 进行初始化
     * @param eventCounts 事件 计数器对象 代表记录了 本次command 执行时 产生的各种事件数量
     * @param startTimestamp command 开始执行的 时间戳
     * @param executionLatency  执行时的延迟
     * @param userThreadLatency  线程池中的延迟
     * @param failedExecutionException  执行过程中出现的异常
     * @param executionException   执行出现的异常
     * @param executionOccurred  是否 执行完成
     * @param isExecutedInThread 是否在线程池中执行
     * @param collapserKey  碰撞键
     */
    private ExecutionResult(EventCounts eventCounts, long startTimestamp, int executionLatency,
                            int userThreadLatency, Exception failedExecutionException, Exception executionException,
                            boolean executionOccurred, boolean isExecutedInThread, HystrixCollapserKey collapserKey) {
        this.eventCounts = eventCounts;
        this.startTimestamp = startTimestamp;
        this.executionLatency = executionLatency;
        this.userThreadLatency = userThreadLatency;
        this.failedExecutionException = failedExecutionException;
        this.executionException = executionException;
        this.executionOccurred = executionOccurred;
        this.isExecutedInThread = isExecutedInThread;
        this.collapserKey = collapserKey;
    }

    // we can return a static version since it's immutable
    // 可以立即获得一个 静态的版本 ???
    static ExecutionResult EMPTY = ExecutionResult.from();

    /**
     * 根据传入的 事件 数量 来生成  result 对象
     * Eventcounter 对象 就是多个事件 发生次数 总和
     * @param eventTypes
     * @return
     */
    public static ExecutionResult from(HystrixEventType... eventTypes) {
        // 如果是从缓存中获取 这里是false
        boolean didExecutionOccur = false;
        for (HystrixEventType eventType: eventTypes) {
            if (didExecutionOccur(eventType)) {
                didExecutionOccur = true;
            }
        }
        return new ExecutionResult(new EventCounts(eventTypes), -1L, -1, -1, null, null, didExecutionOccur, false, null);
    }

    /**
     * 判断是否出现了异常
     * @param eventType
     * @return
     */
    private static boolean didExecutionOccur(HystrixEventType eventType) {
        switch (eventType) {

            // 下面的 几种枚举 都代表 执行完成
            case SUCCESS: return true;
            case FAILURE: return true;
            case BAD_REQUEST: return true;
            case TIMEOUT: return true;
            case CANCELLED: return true;
            default: return false;
        }
    }

    //  调用下面的 方法时 会返回一个 其他属性一致 只有设置的 属性不同的  result 对象

    public ExecutionResult setExecutionOccurred() {
        return new ExecutionResult(eventCounts, startTimestamp, executionLatency, userThreadLatency,
                failedExecutionException, executionException, true, isExecutedInThread, collapserKey);
    }

    public ExecutionResult setExecutionLatency(int executionLatency) {
        return new ExecutionResult(eventCounts, startTimestamp, executionLatency, userThreadLatency,
                failedExecutionException, executionException, executionOccurred, isExecutedInThread, collapserKey);
    }

    public ExecutionResult setException(Exception e) {
        return new ExecutionResult(eventCounts, startTimestamp, executionLatency, userThreadLatency, e,
                executionException, executionOccurred, isExecutedInThread, collapserKey);
    }

    public ExecutionResult setExecutionException(Exception executionException) {
        return new ExecutionResult(eventCounts, startTimestamp, executionLatency, userThreadLatency,
                failedExecutionException, executionException, executionOccurred, isExecutedInThread, collapserKey);
    }

    public ExecutionResult setInvocationStartTime(long startTimestamp) {
        return new ExecutionResult(eventCounts, startTimestamp, executionLatency, userThreadLatency,
                failedExecutionException, executionException, executionOccurred, isExecutedInThread, collapserKey);
    }

    public ExecutionResult setExecutedInThread() {
        return new ExecutionResult(eventCounts, startTimestamp, executionLatency, userThreadLatency,
                failedExecutionException, executionException, executionOccurred, true, collapserKey);
    }

    public ExecutionResult setNotExecutedInThread() {
        return new ExecutionResult(eventCounts, startTimestamp, executionLatency, userThreadLatency,
                failedExecutionException, executionException, executionOccurred, false, collapserKey);
    }

    public ExecutionResult markCollapsed(HystrixCollapserKey collapserKey, int sizeOfBatch) {
        return new ExecutionResult(eventCounts.plus(HystrixEventType.COLLAPSED, sizeOfBatch), startTimestamp, executionLatency, userThreadLatency,
                failedExecutionException, executionException, executionOccurred, isExecutedInThread, collapserKey);
    }

    /**
     * 标记用户本次 执行耗时
     * @param userThreadLatency
     * @return
     */
    public ExecutionResult markUserThreadCompletion(long userThreadLatency) {
        if (startTimestamp > 0 && !isResponseRejected()) {
            /* execution time (must occur before terminal state otherwise a race condition can occur if requested by client) */
            return new ExecutionResult(eventCounts, startTimestamp, executionLatency, (int) userThreadLatency,
                    failedExecutionException, executionException, executionOccurred, isExecutedInThread, collapserKey);
        } else {
            return this;
        }
    }

    /**
     * Creates a new ExecutionResult by adding the defined 'event' to the ones on the current instance.
     * 将传入的事件 添加到 count 中
     * @param eventType event to add
     * @return new {@link ExecutionResult} with event added
     */
    public ExecutionResult addEvent(HystrixEventType eventType) {
        return new ExecutionResult(eventCounts.plus(eventType), startTimestamp, executionLatency,
                userThreadLatency, failedExecutionException, executionException,
                executionOccurred, isExecutedInThread, collapserKey);
    }

    /**
     * 为 结果对象 添加指定的事件
     * @param executionLatency
     * @param eventType
     * @return
     */
    public ExecutionResult addEvent(int executionLatency, HystrixEventType eventType) {
        if (startTimestamp >= 0 && !isResponseRejected()) {
            return new ExecutionResult(eventCounts.plus(eventType), startTimestamp, executionLatency,
                    userThreadLatency, failedExecutionException, executionException,
                    executionOccurred, isExecutedInThread, collapserKey);
        } else {
            return addEvent(eventType);
        }
    }

    public EventCounts getEventCounts() {
        return eventCounts;
    }

    public long getStartTimestamp() {
        return startTimestamp;
    }

    public int getExecutionLatency() {
        return executionLatency;
    }

    public int getUserThreadLatency() {
        return userThreadLatency;
    }

    public long getCommandRunStartTimeInNanos() {
        return startTimestamp * 1000 * 1000;
    }

    public Exception getException() {
        return failedExecutionException;
    }

    public Exception getExecutionException() {
        return executionException;
    }

    public HystrixCollapserKey getCollapserKey() {
        return collapserKey;
    }

    /**
     * 一旦事件计数器中 携带了 semaphoreRejected  就代表 被信号量拒绝了
     * @return
     */
    public boolean isResponseSemaphoreRejected() {
        return eventCounts.contains(HystrixEventType.SEMAPHORE_REJECTED);
    }

    /**
     * 是否被线程池拒绝
     * @return
     */
    public boolean isResponseThreadPoolRejected() {
        return eventCounts.contains(HystrixEventType.THREAD_POOL_REJECTED);
    }

    public boolean isResponseRejected() {
        return isResponseThreadPoolRejected() || isResponseSemaphoreRejected();
    }

    public List<HystrixEventType> getOrderedList() {
        List<HystrixEventType> eventList = new ArrayList<HystrixEventType>();
        for (HystrixEventType eventType: ALL_EVENT_TYPES) {
            // 这个方法的意思 应该就是 按照All_EVENT_TYPES  的 顺序 将 count 中的 事件 添加到一个新的容器中
            if (eventCounts.contains(eventType)) {
                eventList.add(eventType);
            }
        }
        return eventList;
    }

    /**
     * 是否在线程池中执行
     * @return
     */
    public boolean isExecutedInThread() {
        return isExecutedInThread;
    }

    /**
     * 是否正常执行
     * @return
     */
    public boolean executionOccurred() {
        return executionOccurred;
    }

    public boolean containsTerminalEvent() {
        return eventCounts.containsAnyOf(TERMINAL_EVENTS);
    }

    @Override
    public String toString() {
        return "ExecutionResult{" +
                "eventCounts=" + eventCounts +
                ", failedExecutionException=" + failedExecutionException +
                ", executionException=" + executionException +
                ", startTimestamp=" + startTimestamp +
                ", executionLatency=" + executionLatency +
                ", userThreadLatency=" + userThreadLatency +
                ", executionOccurred=" + executionOccurred +
                ", isExecutedInThread=" + isExecutedInThread +
                ", collapserKey=" + collapserKey +
                '}';
    }
}
