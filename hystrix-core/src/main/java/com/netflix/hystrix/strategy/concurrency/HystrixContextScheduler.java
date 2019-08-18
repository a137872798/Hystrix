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
 * hystrix 定时器对象
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

    // 这里就是 把 worker 封装了一层
    @Override
    public Worker createWorker() {
        return new HystrixContextSchedulerWorker(actualScheduler.createWorker());
    }

    /**
     * rxjava 的 调度器 代理对象
     */
    private class HystrixContextSchedulerWorker extends Worker {

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
         *
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

    private static class ThreadPoolScheduler extends Scheduler {

        private final HystrixThreadPool threadPool;
        private final Func0<Boolean> shouldInterruptThread;

        public ThreadPoolScheduler(HystrixThreadPool threadPool, Func0<Boolean> shouldInterruptThread) {
            this.threadPool = threadPool;
            this.shouldInterruptThread = shouldInterruptThread;
        }

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
     */
    private static class ThreadPoolWorker extends Worker {

        private final HystrixThreadPool threadPool;
        private final CompositeSubscription subscription = new CompositeSubscription();
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
