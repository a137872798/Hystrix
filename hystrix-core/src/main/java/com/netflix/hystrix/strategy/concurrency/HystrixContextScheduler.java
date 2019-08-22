/**
 * Copyright 2013 Netflix, Inc.
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

import java.util.concurrent.*;

import rx.*;
import rx.functions.Action0;
import rx.functions.Func0;
import rx.internal.schedulers.ScheduledAction;
import rx.subscriptions.*;

import com.netflix.hystrix.HystrixThreadPool;
import com.netflix.hystrix.strategy.HystrixPlugins;

/**
 * Wrap a {@link Scheduler} so that scheduled actions are wrapped with {@link HystrixContexSchedulerAction} so that
 * the {@link HystrixRequestContext} is properly copied across threads (if they are used by the {@link Scheduler}).
 * hystrix 定时器对象  在 rxjava 中 将任务提交给 scheduler  实质上 该任务 会转交给 内部的 Worker 对象
 */
public class HystrixContextScheduler extends Scheduler {

    /**
     * 存放线程池相关属性
     */
    private final HystrixConcurrencyStrategy concurrencyStrategy;
    /**
     * rxjava 中的 scheduler 对象
     */
    private final Scheduler actualScheduler;
    /**
     * 封装了 jdk 线程池对象 以及 统计对象和 hystrix 属性对象
     */
    private final HystrixThreadPool threadPool;

    public HystrixContextScheduler(Scheduler scheduler) {
        this.actualScheduler = scheduler;
        this.concurrencyStrategy = HystrixPlugins.getInstance().getConcurrencyStrategy();
        this.threadPool = null;
    }

    public HystrixContextScheduler(HystrixConcurrencyStrategy concurrencyStrategy, Scheduler scheduler) {
        this.actualScheduler = scheduler;
        this.concurrencyStrategy = concurrencyStrategy;
        this.threadPool = null;
    }

    public HystrixContextScheduler(HystrixConcurrencyStrategy concurrencyStrategy, HystrixThreadPool threadPool) {
        this(concurrencyStrategy, threadPool, new Func0<Boolean>() {
            @Override
            public Boolean call() {
                return true;
            }
        });
    }

    public HystrixContextScheduler(HystrixConcurrencyStrategy concurrencyStrategy, HystrixThreadPool threadPool, Func0<Boolean> shouldInterruptThread) {
        this.concurrencyStrategy = concurrencyStrategy;
        this.threadPool = threadPool;
        // 生成线程池调度器  shouldInterruptThread 代表是否应该发起 中断当前线程的 指令
        this.actualScheduler = new ThreadPoolScheduler(threadPool, shouldInterruptThread);
    }

    /**
     * 这里就是 把 worker 封装了一层
     * @return
     */
    @Override
    public Worker createWorker() {
        return new HystrixContextSchedulerWorker(actualScheduler.createWorker());
    }

    /**
     * rxjava 的 调度器 代理对象 该对象本身拓展了 worker对象
     */
    private class HystrixContextSchedulerWorker extends Worker {

        /**
         * 该 worker 对象对应的是 hystrix封装过的  ThreadPoolWorker 对象
         */
        private final Worker worker;

        private HystrixContextSchedulerWorker(Worker actualWorker) {
            this.worker = actualWorker;
        }

        @Override
        public void unsubscribe() {
            worker.unsubscribe();
        }

        @Override
        public boolean isUnsubscribed() {
            return worker.isUnsubscribed();
        }

        /**
         * 针对 该worker 提交任务的时候 会判断 当前队列是否还有足够的空间 不足的话 就抛出异常
         * @param action
         * @param delayTime
         * @param unit
         * @return
         */
        @Override
        public Subscription schedule(Action0 action, long delayTime, TimeUnit unit) {
            if (threadPool != null) {
                if (!threadPool.isQueueSpaceAvailable()) {
                    throw new RejectedExecutionException("Rejected command because thread-pool queueSize is at rejection threshold.");
                }
            }
            // 提交任务到线程池  HystrixContexSchedulerAction 对象在 执行时 会处于 当前的上下文环境中 而不是 线程池内部的上下文环境 那么上下文是否会遇到并发问题呢 在
            // 上下文对象中可以看到 context 中维护了一个并发容器对象 就是为了针对这种情况
            // 因为 action 对象 应该是在一个其他线程执行的 也就获取不到 上下文对象
            return worker.schedule(new HystrixContexSchedulerAction(concurrencyStrategy, action), delayTime, unit);
        }

        @Override
        public Subscription schedule(Action0 action) {
            if (threadPool != null) {
                if (!threadPool.isQueueSpaceAvailable()) {
                    throw new RejectedExecutionException("Rejected command because thread-pool queueSize is at rejection threshold.");
                }
            }
            return worker.schedule(new HystrixContexSchedulerAction(concurrencyStrategy, action));
        }

    }

    /**
     * 线程调度器 对象 拓展了 rxjava 默认的 调度器
     */
    private static class ThreadPoolScheduler extends Scheduler {

        private final HystrixThreadPool threadPool;
        /**
         * 是否应该 提示当前线程中断 默认为ture
         */
        private final Func0<Boolean> shouldInterruptThread;

        public ThreadPoolScheduler(HystrixThreadPool threadPool, Func0<Boolean> shouldInterruptThread) {
            this.threadPool = threadPool;
            this.shouldInterruptThread = shouldInterruptThread;
        }

        /**
         * 将创建的 worker 替换成 hystrix 的 worker
         * @return
         */
        @Override
        public Worker createWorker() {
            return new ThreadPoolWorker(threadPool, shouldInterruptThread);
        }

    }

    /**
     * Purely for scheduling work on a thread-pool.
     * <p>
     * This is not natively supported by RxJava as of 0.18.0 because thread-pools
     * are contrary to sequential execution.
     * <p>
     * For the Hystrix case, each Command invocation has a single action so the concurrency
     * issue is not a problem.
     * 指定了 worker 内部的线程池对象
     */
    private static class ThreadPoolWorker extends Worker {

        /**
         * 指定线程池 内部包含了 hystrix 的prop
         */
        private final HystrixThreadPool threadPool;
        /**
         * 订阅者对象
         */
        private final CompositeSubscription subscription = new CompositeSubscription();
        /**
         * 提示线程是否应该被中断
         */
        private final Func0<Boolean> shouldInterruptThread;

        public ThreadPoolWorker(HystrixThreadPool threadPool, Func0<Boolean> shouldInterruptThread) {
            this.threadPool = threadPool;
            this.shouldInterruptThread = shouldInterruptThread;
        }

        @Override
        public void unsubscribe() {
            subscription.unsubscribe();
        }

        @Override
        public boolean isUnsubscribed() {
            return subscription.isUnsubscribed();
        }

        /**
         * 明确了 在提交任务前 必须要 有订阅者
         * @param action
         * @return
         */
        @Override
        public Subscription schedule(final Action0 action) {
            if (subscription.isUnsubscribed()) {
                // don't schedule, we are unsubscribed
                return Subscriptions.unsubscribed();
            }

            // This is internal RxJava API but it is too useful.
            ScheduledAction sa = new ScheduledAction(action);

            subscription.add(sa);
            sa.addParent(subscription);

            // 提交任务
            ThreadPoolExecutor executor = (ThreadPoolExecutor) threadPool.getExecutor();
            FutureTask<?> f = (FutureTask<?>) executor.submit(sa);
            sa.add(new FutureCompleterWithConfigurableInterrupt(f, shouldInterruptThread, executor));

            return sa;
        }

        @Override
        public Subscription schedule(Action0 action, long delayTime, TimeUnit unit) {
            throw new IllegalStateException("Hystrix does not support delayed scheduling");
        }
    }

    /**
     * Very similar to rx.internal.schedulers.ScheduledAction.FutureCompleter, but with configurable interrupt behavior
     * 代表可打断任务
     */
    private static class FutureCompleterWithConfigurableInterrupt implements Subscription {
        private final FutureTask<?> f;
        private final Func0<Boolean> shouldInterruptThread;
        private final ThreadPoolExecutor executor;

        private FutureCompleterWithConfigurableInterrupt(FutureTask<?> f, Func0<Boolean> shouldInterruptThread, ThreadPoolExecutor executor) {
            this.f = f;
            this.shouldInterruptThread = shouldInterruptThread;
            this.executor = executor;
        }

        /**
         * 当触发取消订阅时 尝试进行打断
         */
        @Override
        public void unsubscribe() {
            executor.remove(f);
            if (shouldInterruptThread.call()) {
                f.cancel(true);
            } else {
                f.cancel(false);
            }
        }

        @Override
        public boolean isUnsubscribed() {
            return f.isCancelled();
        }
    }

}
