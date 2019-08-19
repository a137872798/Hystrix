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
package com.netflix.hystrix.util;

import com.netflix.hystrix.HystrixCollapser;
import com.netflix.hystrix.HystrixCommand;
import com.netflix.hystrix.strategy.HystrixPlugins;
import com.netflix.hystrix.strategy.properties.HystrixPropertiesStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.Reference;
import java.lang.ref.SoftReference;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Timer used by {@link HystrixCommand} to timeout async executions and {@link HystrixCollapser} to trigger batch executions.
 * 定时器对象
 */
public class HystrixTimer {

    private static final Logger logger = LoggerFactory.getLogger(HystrixTimer.class);

    /**
     * 单例模式
     */
    private static HystrixTimer INSTANCE = new HystrixTimer();

    private HystrixTimer() {
        // private to prevent public instantiation
    }

    /**
     * Retrieve the global instance.
     */
    public static HystrixTimer getInstance() {
        return INSTANCE;
    }

    /**
     * Clears all listeners.
     * <p>
     * NOTE: This will result in race conditions if {@link #addTimerListener(com.netflix.hystrix.util.HystrixTimer.TimerListener)} is being concurrently called.
     * </p>
     * 重置定时器
     */
    public static void reset() {
        // 获取当前的 定时器对象
        ScheduledExecutor ex = INSTANCE.executor.getAndSet(null);
        if (ex != null && ex.getThreadPool() != null) {
            // 立即停止当前定时器
            ex.getThreadPool().shutdownNow();
        }
    }

    /**
     * 存放定时器的 原子引用对象
     */
    /* package */ AtomicReference<ScheduledExecutor> executor = new AtomicReference<ScheduledExecutor>();

    /**
     * Add a {@link TimerListener} that will be executed until it is garbage collected or removed by clearing the returned {@link Reference}.
     * <p>
     * NOTE: It is the responsibility of code that adds a listener via this method to clear this listener when completed.
     * <p>
     * <blockquote>
     * 
     * <pre> {@code
     * // add a TimerListener 
     * Reference<TimerListener> listener = HystrixTimer.getInstance().addTimerListener(listenerImpl);
     * 
     * // sometime later, often in a thread shutdown, request cleanup, servlet filter or something similar the listener must be shutdown via the clear() method
     * listener.clear();
     * }</pre>
     * </blockquote>
     * 
     * 
     * @param listener
     *            TimerListener implementation that will be triggered according to its <code>getIntervalTimeInMilliseconds()</code> method implementation.
     * @return reference to the TimerListener that allows cleanup via the <code>clear()</code> method
     * 返回的 监听器对象是 被 特殊引用包裹的 便于被 GC 回收
     */
    public Reference<TimerListener> addTimerListener(final TimerListener listener) {
        // 在添加 监听器的时候 会初始化 定时器
        startThreadIfNeeded();
        // add the listener

        Runnable r = new Runnable() {

            @Override
            public void run() {
                try {
                    listener.tick();
                } catch (Exception e) {
                    logger.error("Failed while ticking TimerListener", e);
                }
            }
        };

        // 根据 listener. tick() 的时间间隔 来 触发 嘀嗒方法
        ScheduledFuture<?> f = executor.get().getThreadPool().scheduleAtFixedRate(r, listener.getIntervalTimeInMilliseconds(), listener.getIntervalTimeInMilliseconds(), TimeUnit.MILLISECONDS);
        return new TimerReference(listener, f);
    }

    /**
     * 为什么要使用软引用 软引用 只有在 GC 快满的时候 才会进行回收
     */
    private static class TimerReference extends SoftReference<TimerListener> {

        private final ScheduledFuture<?> f;

        TimerReference(TimerListener referent, ScheduledFuture<?> f) {
            super(referent);
            this.f = f;
        }

        @Override
        public void clear() {
            super.clear();
            // stop this ScheduledFuture from any further executions
            f.cancel(false);
        }

    }

    /**
     * Since we allow resetting the timer (shutting down the thread) we need to lazily re-start it if it starts being used again.
     * <p>
     * This does the lazy initialization and start of the thread in a thread-safe manner while having little cost the rest of the time.
     * 在添加 监听器的时候同时启动定时器对象
     */
    protected void startThreadIfNeeded() {
        // create and start thread if one doesn't exist
        // 创建 定时器对象 并进行初始化
        while (executor.get() == null || ! executor.get().isInitialized()) {
            if (executor.compareAndSet(null, new ScheduledExecutor())) {
                // initialize the executor that we 'won' setting
                executor.get().initialize();
            }
        }
    }

    /**
     * 定时器对象  封装了 JDK 原生的定时器
     */
    /* package */ static class ScheduledExecutor {
        /**
         * jdk 定时器对象
         */
        /* package */ volatile ScheduledThreadPoolExecutor executor;
        /**
         * 是否已经启用
         */
        private volatile boolean initialized;

        /**
         * We want this only done once when created in compareAndSet so use an initialize method
         * 进行初始化
         */
        public void initialize() {

            // 获取 hystrix 的属性对象
            HystrixPropertiesStrategy propertiesStrategy = HystrixPlugins.getInstance().getPropertiesStrategy();
            int coreSize = propertiesStrategy.getTimerThreadPoolProperties().getCorePoolSize().get();

            ThreadFactory threadFactory = null;
            if (!PlatformSpecific.isAppEngineStandardEnvironment()) {
                threadFactory = new ThreadFactory() {
                    final AtomicInteger counter = new AtomicInteger();

                    @Override
                    public Thread newThread(Runnable r) {
                        Thread thread = new Thread(r, "HystrixTimer-" + counter.incrementAndGet());
                        thread.setDaemon(true);
                        return thread;
                    }

                };
            } else {
                threadFactory = PlatformSpecific.getAppEngineThreadFactory();
            }

            // 创建线程池对象
            executor = new ScheduledThreadPoolExecutor(coreSize, threadFactory);
            initialized = true;
        }

        public ScheduledThreadPoolExecutor getThreadPool() {
            return executor;
        }

        public boolean isInitialized() {
            return initialized;
        }
    }

    /**
     * 超时监听器对象
     */
    public static interface TimerListener {

        /**
         * The 'tick' is called each time the interval occurs.
         * <p>
         * This method should NOT block or do any work but instead fire its work asynchronously to perform on another thread otherwise it will prevent the Timer from functioning.
         * <p>
         * This contract is used to keep this implementation single-threaded and simplistic.
         * <p>
         * If you need a ThreadLocal set, you can store the state in the TimerListener, then when tick() is called, set the ThreadLocal to your desired value.
         * 每次嘀嗒时 触发
         */
        public void tick();

        /**
         * How often this TimerListener should 'tick' defined in milliseconds.
         * 获取 2个 嘀嗒间的时间间隔
         */
        public int getIntervalTimeInMilliseconds();
    }

}
