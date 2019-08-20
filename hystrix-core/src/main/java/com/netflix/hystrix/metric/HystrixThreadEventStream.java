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

import com.netflix.hystrix.ExecutionResult;
import com.netflix.hystrix.HystrixCollapserKey;
import com.netflix.hystrix.HystrixCommandKey;
import com.netflix.hystrix.HystrixCommandProperties;
import com.netflix.hystrix.HystrixEventType;
import com.netflix.hystrix.HystrixThreadPool;
import com.netflix.hystrix.HystrixThreadPoolKey;
import rx.functions.Action1;
import rx.observers.Subscribers;
import rx.subjects.PublishSubject;
import rx.subjects.Subject;

/**
 * Per-thread event stream.  No synchronization required when writing to it since it's single-threaded.
 *
 * Some threads will be dedicated to a single HystrixCommandKey (a member of a thread-isolated {@link HystrixThreadPool}.
 * However, many situations arise where a single thread may serve many different commands.  Examples include:
 * * Application caller threads (semaphore-isolated commands, or thread-pool-rejections)
 * * Timer threads (timeouts or collapsers)
 * <p>
 * I don't think that a thread-level view is an interesting one to consume (I could be wrong), so at the moment there
 * is no public way to consume it.  I can always add it later, if desired.
 * <p>
 * Instead, this stream writes to the following streams, which have more meaning to metrics consumers:
 * <ul>
 *     <li>{@link HystrixCommandCompletionStream}</li>
 *     <li>{@link HystrixCommandStartStream}</li>
 *     <li>{@link HystrixThreadPoolCompletionStream}</li>
 *     <li>{@link HystrixThreadPoolStartStream}</li>
 *     <li>{@link HystrixCollapserEventStream}</li>
 * </ul>
 *
 * Also note that any observers of this stream do so on the thread that writes the metric.  This is the command caller
 * thread in the SEMAPHORE-isolated case, and the Hystrix thread in the THREAD-isolated case. I determined this to
 * be more efficient CPU-wise than immediately hopping off-thread and doing all the metric calculations in the
 * RxComputationThreadPool.
 * 每个线程维护的自己的事件流
 */
public class HystrixThreadEventStream {
    /**
     * 本线程id
     */
    private final long threadId;
    /**
     * 本线程名
     */
    private final String threadName;

    // K, V 一致代表subject 桥接的 2个 类型是相同的
    // HystrixCommandExecutionStarted 代表执行的线程隔离策略相关信息
    private final Subject<HystrixCommandExecutionStarted, HystrixCommandExecutionStarted> writeOnlyCommandStartSubject;
    // HystrixCommandCompletion 维护了 executionResult
    private final Subject<HystrixCommandCompletion, HystrixCommandCompletion> writeOnlyCommandCompletionSubject;
    // 记录 碰撞事件
    private final Subject<HystrixCollapserEvent, HystrixCollapserEvent> writeOnlyCollapserSubject;

    /**
     * 创建事件流对象
     */
    private static final ThreadLocal<HystrixThreadEventStream> threadLocalStreams = new ThreadLocal<HystrixThreadEventStream>() {
        @Override
        protected HystrixThreadEventStream initialValue() {
            return new HystrixThreadEventStream(Thread.currentThread());
        }
    };

    /**
     * 当 执行 command 时 会从上层 创建一个 HystrixCommandExecutionStarted 对象 并通过 该函数传递到下游
     */
    private static final Action1<HystrixCommandExecutionStarted> writeCommandStartsToShardedStreams = new Action1<HystrixCommandExecutionStarted>() {
        @Override
        public void call(HystrixCommandExecutionStarted event) {
            HystrixCommandStartStream commandStartStream = HystrixCommandStartStream.getInstance(event.getCommandKey());
            // 下发数据
            commandStartStream.write(event);

            // 如果是通过线程 隔离 command  如果是 信号量 这里 不再生成 ThreadPoolStartStream
            if (event.isExecutedInThread()) {
                // 同时下发 线程相关的数据
                HystrixThreadPoolStartStream threadPoolStartStream = HystrixThreadPoolStartStream.getInstance(event.getThreadPoolKey());
                // 下发数据 这样 订阅 ThreadPoolStartStream 的对象 就可以 接收到 创建线程的相关数据
                threadPoolStartStream.write(event);
            }
        }
    };

    private static final Action1<HystrixCommandCompletion> writeCommandCompletionsToShardedStreams = new Action1<HystrixCommandCompletion>() {
        @Override
        public void call(HystrixCommandCompletion commandCompletion) {
            HystrixCommandCompletionStream commandStream = HystrixCommandCompletionStream.getInstance(commandCompletion.getCommandKey());
            commandStream.write(commandCompletion);

            if (commandCompletion.isExecutedInThread() || commandCompletion.isResponseThreadPoolRejected()) {
                HystrixThreadPoolCompletionStream threadPoolStream = HystrixThreadPoolCompletionStream.getInstance(commandCompletion.getThreadPoolKey());
                threadPoolStream.write(commandCompletion);
            }
        }
    };

    private static final Action1<HystrixCollapserEvent> writeCollapserExecutionsToShardedStreams = new Action1<HystrixCollapserEvent>() {
        @Override
        public void call(HystrixCollapserEvent collapserEvent) {
            HystrixCollapserEventStream collapserStream = HystrixCollapserEventStream.getInstance(collapserEvent.getCollapserKey());
            collapserStream.write(collapserEvent);
        }
    };

    /**
     * 线程事件流对象  传入当前线程进行初始化
     * @param thread
     */
    /* package */ HystrixThreadEventStream(Thread thread) {
        // 设置线程 id 和 线程 name
        this.threadId = thread.getId();
        this.threadName = thread.getName();

        // 创建 对应的 subject 接受到 订阅后的所有数据
        writeOnlyCommandStartSubject = PublishSubject.create();
        writeOnlyCommandCompletionSubject = PublishSubject.create();
        writeOnlyCollapserSubject = PublishSubject.create();

        // 开启背压 设置 onNext 事件
        writeOnlyCommandStartSubject
                .onBackpressureBuffer()
                .doOnNext(writeCommandStartsToShardedStreams)
                // 推测是 非线程安全的 订阅
                .unsafeSubscribe(Subscribers.empty());

        // 该对象 用于接收 到 command 执行完后 封装的 CommandComplete 对象 并发射到下游的订阅者
        writeOnlyCommandCompletionSubject
                .onBackpressureBuffer()
                .doOnNext(writeCommandCompletionsToShardedStreams)
                .unsafeSubscribe(Subscribers.empty());

        writeOnlyCollapserSubject
                .onBackpressureBuffer()
                .doOnNext(writeCollapserExecutionsToShardedStreams)
                .unsafeSubscribe(Subscribers.empty());
    }

    public static HystrixThreadEventStream getInstance() {
        return threadLocalStreams.get();
    }

    public void shutdown() {
        writeOnlyCommandStartSubject.onCompleted();
        writeOnlyCommandCompletionSubject.onCompleted();
        writeOnlyCollapserSubject.onCompleted();
    }

    /**
     * 当命令开始执行时 触发
     * @param commandKey
     * @param threadPoolKey
     * @param isolationStrategy
     * @param currentConcurrency  这里还传入了 当前command 的 并发数
     */
    public void commandExecutionStarted(HystrixCommandKey commandKey, HystrixThreadPoolKey threadPoolKey,
                                        HystrixCommandProperties.ExecutionIsolationStrategy isolationStrategy, int currentConcurrency) {
        HystrixCommandExecutionStarted event = new HystrixCommandExecutionStarted(commandKey, threadPoolKey, isolationStrategy, currentConcurrency);
        // 将事件发送到下游
        writeOnlyCommandStartSubject.onNext(event);
    }

    /**
     * 触发 代表command 执行完成
     * @param executionResult
     * @param commandKey
     * @param threadPoolKey
     */
    public void executionDone(ExecutionResult executionResult, HystrixCommandKey commandKey, HystrixThreadPoolKey threadPoolKey) {
        // 将 result对象 对应的 2个 缓存键 以及 当前线程维护的 上下文对象 封装成 commandCompletion 对象
        HystrixCommandCompletion event = HystrixCommandCompletion.from(executionResult, commandKey, threadPoolKey);
        // emit 元素 对象到 下游 因为该对象是 subject 可以通过手动触发onNext 它会自动将 数据发射到下游
        writeOnlyCommandCompletionSubject.onNext(event);
    }

    public void collapserResponseFromCache(HystrixCollapserKey collapserKey) {
        HystrixCollapserEvent collapserEvent = HystrixCollapserEvent.from(collapserKey, HystrixEventType.Collapser.RESPONSE_FROM_CACHE, 1);
        writeOnlyCollapserSubject.onNext(collapserEvent);
    }

    public void collapserBatchExecuted(HystrixCollapserKey collapserKey, int batchSize) {
        HystrixCollapserEvent batchExecution = HystrixCollapserEvent.from(collapserKey, HystrixEventType.Collapser.BATCH_EXECUTED, 1);
        HystrixCollapserEvent batchAdditions = HystrixCollapserEvent.from(collapserKey, HystrixEventType.Collapser.ADDED_TO_BATCH, batchSize);
        writeOnlyCollapserSubject.onNext(batchExecution);
        writeOnlyCollapserSubject.onNext(batchAdditions);
    }

    @Override
    public String toString() {
        return "HystrixThreadEventStream (" + threadId + " - " + threadName + ")";
    }
}
