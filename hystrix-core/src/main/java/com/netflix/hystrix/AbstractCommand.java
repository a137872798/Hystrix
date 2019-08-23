/**
 * Copyright 2013 Netflix, Inc.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.hystrix;

import com.netflix.hystrix.HystrixCircuitBreaker.NoOpCircuitBreaker;
import com.netflix.hystrix.HystrixCommandProperties.ExecutionIsolationStrategy;
import com.netflix.hystrix.exception.ExceptionNotWrappedByHystrix;
import com.netflix.hystrix.exception.HystrixBadRequestException;
import com.netflix.hystrix.exception.HystrixRuntimeException;
import com.netflix.hystrix.exception.HystrixRuntimeException.FailureType;
import com.netflix.hystrix.exception.HystrixTimeoutException;
import com.netflix.hystrix.strategy.HystrixPlugins;
import com.netflix.hystrix.strategy.concurrency.HystrixConcurrencyStrategy;
import com.netflix.hystrix.strategy.concurrency.HystrixContextRunnable;
import com.netflix.hystrix.strategy.concurrency.HystrixRequestContext;
import com.netflix.hystrix.strategy.eventnotifier.HystrixEventNotifier;
import com.netflix.hystrix.strategy.executionhook.HystrixCommandExecutionHook;
import com.netflix.hystrix.strategy.metrics.HystrixMetricsPublisherFactory;
import com.netflix.hystrix.strategy.properties.HystrixPropertiesFactory;
import com.netflix.hystrix.strategy.properties.HystrixPropertiesStrategy;
import com.netflix.hystrix.strategy.properties.HystrixProperty;
import com.netflix.hystrix.util.HystrixTimer;
import com.netflix.hystrix.util.HystrixTimer.TimerListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rx.Notification;
import rx.Observable;
import rx.Observable.Operator;
import rx.Subscriber;
import rx.Subscription;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.functions.Func0;
import rx.functions.Func1;
import rx.subjects.ReplaySubject;
import rx.subscriptions.CompositeSubscription;

import java.lang.ref.Reference;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 命令对象 骨架类 hystrix 的2个核心类都是 继承这个类
 * @param <R>
 */

/* package */abstract class AbstractCommand<R> implements HystrixInvokableInfo<R>, HystrixObservable<R> {
    private static final Logger logger = LoggerFactory.getLogger(AbstractCommand.class);

    /**
     * 断路器
     */
    protected final HystrixCircuitBreaker circuitBreaker;
    /**
     * hystrix 线程池对象 请求应该都会发送到该对象中 执行
     */
    protected final HystrixThreadPool threadPool;
    /**
     * 线程池键
     */
    protected final HystrixThreadPoolKey threadPoolKey;
    /**
     * command 相关信息
     */
    protected final HystrixCommandProperties properties;

    /**
     * 针对超时状态
     */
    protected enum TimedOutStatus {
        /**
         * 未执行
         */
        NOT_EXECUTED,
        /**
         * 已完成
         */
        COMPLETED,
        /**
         * 超时
         */
        TIMED_OUT
    }

    /**
     * 当前命令状态
     */
    protected enum CommandState {
        /**
         * command 未开始
         */
        NOT_STARTED,
        /**
         * 观察链被创建
         */
        OBSERVABLE_CHAIN_CREATED,
        /**
         * 用户代码 已执行
         */
        USER_CODE_EXECUTED,
        /**
         * 未订阅
         */
        UNSUBSCRIBED,
        /**
         * 结束
         */
        TERMINAL
    }

    /**
     * 当前线程状态
     */
    protected enum ThreadState {
        /**
         * 未使用线程池
         */
        NOT_USING_THREAD,
        /**
         * 开启线程池
         */
        STARTED,
        /**
         * 还没有订阅者
         */
        UNSUBSCRIBED, TERMINAL
    }

    /**
     * 命令统计对象  内部维护了一个计数器对象  用于统计一系列数据 就是通过 rxJava 的 响应式编程
     */
    protected final HystrixCommandMetrics metrics;

    /**
     * 命令键
     */
    protected final HystrixCommandKey commandKey;
    /**
     * 命令组
     */
    protected final HystrixCommandGroupKey commandGroup;

    /**
     * Plugin implementations
     * 事件通知对象 用于写入插件
     */
    protected final HystrixEventNotifier eventNotifier;
    /**
     * 当前并发策略
     */
    protected final HystrixConcurrencyStrategy concurrencyStrategy;
    /**
     * 命令执行钩子  作用于不同的 生命周期中
     */
    protected final HystrixCommandExecutionHook executionHook;

    /* FALLBACK Semaphore */
    /**
     * 针对 执行失败 尝试进行回退的信号量
     */
    protected final TryableSemaphore fallbackSemaphoreOverride;
    /* each circuit has a semaphore to restrict concurrent fallback execution */
    /**
     * 每个循环器 有自己的 回退信号量???  回退次数可能是指 没有获取到 信号量的请求对象 重试 尝试获得 重新执行机会
     */
    protected static final ConcurrentHashMap<String, TryableSemaphore> fallbackSemaphorePerCircuit = new ConcurrentHashMap<String, TryableSemaphore>();
    /* END FALLBACK Semaphore */

    /* EXECUTION Semaphore */
    /**
     * 重试信号量 (正常执行)
     */
    protected final TryableSemaphore executionSemaphoreOverride;
    /* each circuit has a semaphore to restrict concurrent fallback execution */
    // 静态成员变量配合 缓存容器
    protected static final ConcurrentHashMap<String, TryableSemaphore> executionSemaphorePerCircuit = new ConcurrentHashMap<String, TryableSemaphore>();
    /* END EXECUTION Semaphore */

    /**
     * 时间监听器对象 一般是 配合 HystrixTimer 对象 (hystrixTimer 是一个 定时器对象 当添加 listener 到 timer 时 就会定时执行 listener.tick() 的逻辑)
     * 目前内部会维护一个定期扫描是否 超时 并对下游发出异常的timer listener 对象
     */
    protected final AtomicReference<Reference<TimerListener>> timeoutTimer = new AtomicReference<Reference<TimerListener>>();

    /**
     * 命令状态 就是对应 未开始 执行中 等等
     */
    protected AtomicReference<CommandState> commandState = new AtomicReference<CommandState>(CommandState.NOT_STARTED);

    /**
     * 线程池状态  未使用线程池 已使用线程池 未订阅 等
     */
    protected AtomicReference<ThreadState> threadState = new AtomicReference<ThreadState>(ThreadState.NOT_USING_THREAD);

    /*
     * {@link ExecutionResult} refers to what happened as the user-provided code ran.  If request-caching is used,
     * then multiple command instances will have a reference to the same {@link ExecutionResult}.  So all values there
     * should be the same, even in the presence of request-caching.
     *
     * If some values are not properly shareable, then they belong on the command instance, so they are not visible to
     * other commands.
     *
     * Examples: RESPONSE_FROM_CACHE, CANCELLED HystrixEventTypes
     * 执行结果 内部维护一个 counter 对象 记录本次调用相关的计数 (比如 成功次数 失败次数等)
     */
    protected volatile ExecutionResult executionResult = ExecutionResult.EMPTY; //state on shared execution

    /**
     * 是否从缓存中获取 resExecutionResult
     */
    protected volatile boolean isResponseFromCache = false;
    /**
     * 当触发cancel 事件时设置该结果对象
     */
    protected volatile ExecutionResult executionResultAtTimeOfCancellation;
    /**
     * 命令开始的时间
     */
    protected volatile long commandStartTimestamp = -1L;

    /* If this command executed and timed-out */
    // 超时状态的 原子引用
    protected final AtomicReference<TimedOutStatus> isCommandTimedOut = new AtomicReference<TimedOutStatus>(TimedOutStatus.NOT_EXECUTED);
    /**
     * 当 执行完 command 时 执行的 函数
     */
    protected volatile Action0 endCurrentThreadExecutingCommand;

    /**
     * Instance of RequestCache logic
     * 请求缓存对象  内部核心键值对就是  ValueCacheKey, HystrixCachedObservable<?>  ValueCacheKey 是使用 RequestKey 生成的 保证一个 request 对应一个 observable
     */
    protected final HystrixRequestCache requestCache;
    /**
     * 请求日志  在执行某个命令时 就会将日志信息输入到 该 requestLog 对象中
     */
    protected final HystrixRequestLog currentRequestLog;

    // this is a micro-optimization but saves about 1-2microseconds (on 2011 MacBook Pro) 
    // on the repetitive string processing that will occur on the same classes over and over again
    /**
     *  默认的缓存对象  class 为key value 为 class.simpleClass
     */
    private static ConcurrentHashMap<Class<?>, String> defaultNameCache = new ConcurrentHashMap<Class<?>, String>();

    /**
     * 回退命令容器 应该是 代表 某条命令是否回退
     */
    protected static ConcurrentHashMap<HystrixCommandKey, Boolean> commandContainsFallback = new ConcurrentHashMap<HystrixCommandKey, Boolean>();

    /**
     * 尝试 获取 指定 class 类的 simpleName  这个对象 为什么要做缓存 ???
     * @param cls
     * @return
     */
    /* package */
    static String getDefaultNameFromClass(Class<?> cls) {
        // 传入class 对象获取缓存键
        String fromCache = defaultNameCache.get(cls);
        if (fromCache != null) {
            return fromCache;
        }
        // generate the default
        // default HystrixCommandKey to use if the method is not overridden
        // 截取后存入
        String name = cls.getSimpleName();
        if (name.equals("")) {
            // we don't have a SimpleName (anonymous inner class) so use the full class name
            name = cls.getName();
            name = name.substring(name.lastIndexOf('.') + 1, name.length());
        }
        defaultNameCache.put(cls, name);
        return name;
    }

    /**
     * 构建 command 骨架类
     * @param group 该对象也是只有一个name 属性
     * @param key 该对象就一个name 属性
     * @param threadPoolKey 也是只有一个name 属性
     * @param circuitBreaker  熔断器对象
     * @param threadPool  线程池 对象
     * @param commandPropertiesDefaults  command 默认的属性
     * @param threadPoolPropertiesDefaults  线程池 默认的属性
     * @param metrics   测量对象
     * @param fallbackSemaphore   处理 回退 的信号量对象
     * @param executionSemaphore    处理 执行的 信号量对象
     * @param propertiesStrategy   属性策略对象
     * @param executionHook  执行钩子
     */
    protected AbstractCommand(HystrixCommandGroupKey group, HystrixCommandKey key, HystrixThreadPoolKey threadPoolKey, HystrixCircuitBreaker circuitBreaker, HystrixThreadPool threadPool,
                              HystrixCommandProperties.Setter commandPropertiesDefaults, HystrixThreadPoolProperties.Setter threadPoolPropertiesDefaults,
                              HystrixCommandMetrics metrics, TryableSemaphore fallbackSemaphore, TryableSemaphore executionSemaphore,
                              HystrixPropertiesStrategy propertiesStrategy, HystrixCommandExecutionHook executionHook) {

        // 非空校验  commandGroupKey 是不能为空的
        this.commandGroup = initGroupKey(group);
        // 如果 key 为空使用 className 作为缓存命令键名   那么单个command 执行多次 会使用相同的 commandKey
        this.commandKey = initCommandKey(key, getClass());
        // 如果 prop 为空 使用 commandPropertiesDefaults 去初始化一个新对象
        this.properties = initCommandProperties(this.commandKey, propertiesStrategy, commandPropertiesDefaults);
        // 初始化线程池Key
        this.threadPoolKey = initThreadPoolKey(threadPoolKey, this.commandGroup, this.properties.executionIsolationThreadPoolKeyOverride().get());
        // 初始化 测量对象
        this.metrics = initMetrics(metrics, this.commandGroup, this.threadPoolKey, this.commandKey, this.properties);
        // 初始化 熔断器对象  默认开启   并且会需要metrics 中的 HealthStrem 对象 通过每 roll 的数据请求成功率来判断是否开启熔断器
        this.circuitBreaker = initCircuitBreaker(this.properties.circuitBreakerEnabled().get(), circuitBreaker, this.commandGroup, this.commandKey, this.properties, this.metrics);
        // 初始化线程池对象 用户可以自定义
        this.threadPool = initThreadPool(threadPool, this.threadPoolKey, threadPoolPropertiesDefaults);

        //Strategies from plugins
        this.eventNotifier = HystrixPlugins.getInstance().getEventNotifier();
        this.concurrencyStrategy = HystrixPlugins.getInstance().getConcurrencyStrategy();

        // 初始化 有关 command 的数据发布对象
        HystrixMetricsPublisherFactory.createOrRetrievePublisherForCommand(this.commandKey, this.commandGroup, this.metrics, this.circuitBreaker, this.properties);
        // 初始化 钩子对象
        this.executionHook = initExecutionHook(executionHook);

        // 针对请求 的 缓存对象 这里要确保 concurrencyStrategy 是一致的  针对 commandKey 相同的情况 requestCache 是一样的对象 这样就能保证  多个 command 实例（但是 commandKey一样） 能使用相同的 缓存
        this.requestCache = HystrixRequestCache.getInstance(this.commandKey, this.concurrencyStrategy);
        // 根据 请求对象 在 执行过程中的 情况 记录 日志 默认打开
        this.currentRequestLog = initRequestLog(this.properties.requestLogEnabled().get(), this.concurrencyStrategy);

        /* fallback semaphore override if applicable */
        this.fallbackSemaphoreOverride = fallbackSemaphore;

        /* execution semaphore override if applicable */
        this.executionSemaphoreOverride = executionSemaphore;
    }

    /**
     * 非空校验
     * @param fromConstructor
     * @return
     */
    private static HystrixCommandGroupKey initGroupKey(final HystrixCommandGroupKey fromConstructor) {
        if (fromConstructor == null) {
            throw new IllegalStateException("HystrixCommandGroup can not be NULL");
        } else {
            return fromConstructor;
        }
    }

    private static HystrixCommandKey initCommandKey(final HystrixCommandKey fromConstructor, Class<?> clazz) {
        if (fromConstructor == null || fromConstructor.name().trim().equals("")) {
            final String keyName = getDefaultNameFromClass(clazz);
            return HystrixCommandKey.Factory.asKey(keyName);
        } else {
            return fromConstructor;
        }
    }

    /**
     * 获取属性对象  用户创建比较简单的 command 对象时 就是 只设置一个 commandKey
     * @param commandKey  notnull
     * @param propertiesStrategy  nullable
     * @param commandPropertiesDefaults  nullable
     * @return
     */
    private static HystrixCommandProperties initCommandProperties(HystrixCommandKey commandKey, HystrixPropertiesStrategy propertiesStrategy, HystrixCommandProperties.Setter commandPropertiesDefaults) {
        // 如果没有设置 hystrix 的 属性对象 就使用 commandKey 去初始化一个新的
        if (propertiesStrategy == null) {
            return HystrixPropertiesFactory.getCommandProperties(commandKey, commandPropertiesDefaults);
        } else {
            // used for unit testing
            // 这里代表 使用 commandPropertiesDefaults 中的部分属性去 替换 propertiesStrategy
            return propertiesStrategy.getCommandProperties(commandKey, commandPropertiesDefaults);
        }
    }

    /*
     * ThreadPoolKey
     *
     * This defines which thread-pool this command should run on.
     *
     * It uses the HystrixThreadPoolKey if provided, then defaults to use HystrixCommandGroup.
     *
     * It can then be overridden by a property if defined so it can be changed at runtime.
     * 初始化 线程池 Key  默认情况下 线程池 Key 是不用被重写的
     */
    private static HystrixThreadPoolKey initThreadPoolKey(HystrixThreadPoolKey threadPoolKey, HystrixCommandGroupKey groupKey, String threadPoolKeyOverride) {
        if (threadPoolKeyOverride == null) {
            // we don't have a property overriding the value so use either HystrixThreadPoolKey or HystrixCommandGroup
            if (threadPoolKey == null) {
                /* use HystrixCommandGroup if HystrixThreadPoolKey is null */
                return HystrixThreadPoolKey.Factory.asKey(groupKey.name());
            } else {
                return threadPoolKey;
            }
        } else {
            // we have a property defining the thread-pool so use it instead
            return HystrixThreadPoolKey.Factory.asKey(threadPoolKeyOverride);
        }
    }

    /**
     * 初始化 hystrix 的初始化对象  metrics 中维护了 统计各种数据的 数据流对象  fromConstructor 代表在 构造command 时 有没有传入 执行的 metrics 对象
     */
    private static HystrixCommandMetrics initMetrics(HystrixCommandMetrics fromConstructor, HystrixCommandGroupKey groupKey,
                                                     HystrixThreadPoolKey threadPoolKey, HystrixCommandKey commandKey,
                                                     HystrixCommandProperties properties) {
        if (fromConstructor == null) {
            return HystrixCommandMetrics.getInstance(commandKey, groupKey, threadPoolKey, properties);
        } else {
            return fromConstructor;
        }
    }

    /**
     * 初始化断路器对象
     * @param enabled
     * @param fromConstructor
     * @param groupKey
     * @param commandKey
     * @param properties
     * @param metrics
     * @return
     */
    private static HystrixCircuitBreaker initCircuitBreaker(boolean enabled, HystrixCircuitBreaker fromConstructor,
                                                            HystrixCommandGroupKey groupKey, HystrixCommandKey commandKey,
                                                            HystrixCommandProperties properties, HystrixCommandMetrics metrics) {
        if (enabled) {
            if (fromConstructor == null) {
                // get the default implementation of HystrixCircuitBreaker
                return HystrixCircuitBreaker.Factory.getInstance(commandKey, groupKey, properties, metrics);
            } else {
                return fromConstructor;
            }
        } else {
            return new NoOpCircuitBreaker();
        }
    }

    /**
     * 执行钩子也是由用户实现的 可以在指定生命周期执行对应的 处理
     * @param fromConstructor
     * @return
     */
    private static HystrixCommandExecutionHook initExecutionHook(HystrixCommandExecutionHook fromConstructor) {
        if (fromConstructor == null) {
            return new ExecutionHookDeprecationWrapper(HystrixPlugins.getInstance().getCommandExecutionHook());
        } else {
            // used for unit testing
            if (fromConstructor instanceof ExecutionHookDeprecationWrapper) {
                return fromConstructor;
            } else {
                return new ExecutionHookDeprecationWrapper(fromConstructor);
            }
        }
    }

    private static HystrixThreadPool initThreadPool(HystrixThreadPool fromConstructor, HystrixThreadPoolKey threadPoolKey, HystrixThreadPoolProperties.Setter threadPoolPropertiesDefaults) {
        if (fromConstructor == null) {
            // get the default implementation of HystrixThreadPool
            return HystrixThreadPool.Factory.getInstance(threadPoolKey, threadPoolPropertiesDefaults);
        } else {
            return fromConstructor;
        }
    }

    private static HystrixRequestLog initRequestLog(boolean enabled, HystrixConcurrencyStrategy concurrencyStrategy) {
        if (enabled) {
            /* store reference to request log regardless of which thread later hits it */
            return HystrixRequestLog.getCurrentRequest(concurrencyStrategy);
        } else {
            return null;
        }
    }

    /**
     * Allow the Collapser to mark this command instance as being used for a collapsed request and how many requests were collapsed.
     *
     * @param sizeOfBatch number of commands in request batch
     *                    当需要mark 碰撞 命令时 调用 markEvent
     */
    /* package */void markAsCollapsedCommand(HystrixCollapserKey collapserKey, int sizeOfBatch) {
        eventNotifier.markEvent(HystrixEventType.COLLAPSED, this.commandKey);
        // 在 原有的 result 对象上 修改指定的属性 并返回新对象
        executionResult = executionResult.markCollapsed(collapserKey, sizeOfBatch);
    }

    /**
     * Used for asynchronous execution of command with a callback by subscribing to the {@link Observable}.
     * <p>
     * This eagerly starts execution of the command the same as {@link HystrixCommand#queue()} and {@link HystrixCommand#execute()}.
     * <p>
     * A lazy {@link Observable} can be obtained from {@link #toObservable()}.
     * <p>
     * See https://github.com/Netflix/RxJava/wiki for more information.
     *
     * @return {@code Observable<R>} that executes and calls back with the result of command execution or a fallback if the command fails for any reason.
     * @throws HystrixRuntimeException
     *             if a fallback does not exist
     *             <p>
     *             <ul>
     *             <li>via {@code Observer#onError} if a failure occurs</li>
     *             <li>or immediately if the command can not be queued (such as short-circuited, thread-pool/semaphore rejected)</li>
     *             </ul>
     * @throws HystrixBadRequestException
     *             via {@code Observer#onError} if invalid arguments or state were used representing a user failure, not a system failure
     * @throws IllegalStateException
     *             if invoked more than once
     *             获取 数据源对象  这里返回的 是一个 hot 数据源
     */
    public Observable<R> observe() {
        // us a ReplaySubject to buffer the eagerly subscribed-to Observable
        // 代表 订阅者 会收到 全部数据
        ReplaySubject<R> subject = ReplaySubject.create();
        // eagerly kick off subscription
        // 将 subject 订阅到 该command 上 (toObservable 代表本command 返回的可观察数据源)
        final Subscription sourceSubscription = toObservable().subscribe(subject);
        // return the subject that can be subscribed to later while the execution has already started
        // 为 toObservable 多封装了一层 就是调用返回对象的 unsubscribe 会 取消sourceSubscription 的订阅
        return subject.doOnUnsubscribe(new Action0() {
            @Override
            public void call() {
                sourceSubscription.unsubscribe();
            }
        });
    }

    /**
     * 获取 执行时的 observable
     * @return
     */
    protected abstract Observable<R> getExecutionObservable();

    /**
     * 获取 回退时的 observable
     * @return
     */
    protected abstract Observable<R> getFallbackObservable();

    /**
     * Used for asynchronous execution of command with a callback by subscribing to the {@link Observable}.
     * <p>
     * This lazily starts execution of the command once the {@link Observable} is subscribed to.
     * <p>
     * An eager {@link Observable} can be obtained from {@link #observe()}.
     * <p>
     * See https://github.com/ReactiveX/RxJava/wiki for more information.
     *
     * @return {@code Observable<R>} that executes and calls back with the result of command execution or a fallback if the command fails for any reason.
     * @throws HystrixRuntimeException
     *             if a fallback does not exist
     *             <p>
     *             <ul>
     *             <li>via {@code Observer#onError} if a failure occurs</li>
     *             <li>or immediately if the command can not be queued (such as short-circuited, thread-pool/semaphore rejected)</li>
     *             </ul>
     * @throws HystrixBadRequestException
     *             via {@code Observer#onError} if invalid arguments or state were used representing a user failure, not a system failure
     * @throws IllegalStateException
     *             if invoked more than once
     *             获取执行本 command  返回的 observable 对象
     */
    public Observable<R> toObservable() {
        final AbstractCommand<R> _cmd = this;

        //doOnCompleted handler already did all of the SUCCESS work
        //doOnError handler already did all of the FAILURE/TIMEOUT/REJECTION/BAD_REQUEST work
        //该 action是在 command 执行完之后 触发的
        final Action0 terminateCommandCleanup = new Action0() {

            @Override
            public void call() {
                // 如果当前创建完成 并设置成 terminal 成功 代表 一个 command 开始执行并完成
                if (_cmd.commandState.compareAndSet(CommandState.OBSERVABLE_CHAIN_CREATED, CommandState.TERMINAL)) {
                    handleCommandEnd(false); //user code never ran
                    // 代表用户代表 执行之后设置成 完成
                } else if (_cmd.commandState.compareAndSet(CommandState.USER_CODE_EXECUTED, CommandState.TERMINAL)) {
                    handleCommandEnd(true); //user code did run
                }
            }
        };

        //mark the command as CANCELLED and store the latency (in addition to standard cleanup)
        // 取消订阅时 触发
        final Action0 unsubscribeCommandCleanup = new Action0() {
            @Override
            public void call() {
                // 如果当前断路器是半开状态就设置成开启 否则不处理
                circuitBreaker.markNonSuccess();
                // 如果是从 chain 刚创建
                if (_cmd.commandState.compareAndSet(CommandState.OBSERVABLE_CHAIN_CREATED, CommandState.UNSUBSCRIBED)) {
                    // 判断是否 包含终止事件
                    if (!_cmd.executionResult.containsTerminalEvent()) {
                        // 通知监听器 触发关闭事件
                        _cmd.eventNotifier.markEvent(HystrixEventType.CANCELLED, _cmd.commandKey);
                        try {
                            // 代表 该 command 对象取消订阅
                            executionHook.onUnsubscribe(_cmd);
                        } catch (Throwable hookEx) {
                            logger.warn("Error calling HystrixCommandExecutionHook.onUnsubscribe", hookEx);
                        }
                        // 根据 event 事件 生成一个新的结果对象
                        _cmd.executionResultAtTimeOfCancellation = _cmd.executionResult
                                .addEvent((int) (System.currentTimeMillis() - _cmd.commandStartTimestamp), HystrixEventType.CANCELLED);
                    }
                    // 将 result 对象 下发到下游
                    handleCommandEnd(false); //user code never ran
                    // 如果是 执行完用户代码之后 调用 取消订阅
                } else if (_cmd.commandState.compareAndSet(CommandState.USER_CODE_EXECUTED, CommandState.UNSUBSCRIBED)) {
                    if (!_cmd.executionResult.containsTerminalEvent()) {
                        _cmd.eventNotifier.markEvent(HystrixEventType.CANCELLED, _cmd.commandKey);
                        try {
                            executionHook.onUnsubscribe(_cmd);
                        } catch (Throwable hookEx) {
                            logger.warn("Error calling HystrixCommandExecutionHook.onUnsubscribe", hookEx);
                        }
                        _cmd.executionResultAtTimeOfCancellation = _cmd.executionResult
                                .addEvent((int) (System.currentTimeMillis() - _cmd.commandStartTimestamp), HystrixEventType.CANCELLED);
                    }
                    handleCommandEnd(true); //user code did run
                }
            }
        };

        /**
         * 应用 hystrix 语义  这里会调用 command实现类的 核心方法
         */
        final Func0<Observable<R>> applyHystrixSemantics = new Func0<Observable<R>>() {
            @Override
            public Observable<R> call() {
                // 如果 状态被设置成不可订阅 返回空数据源
                if (commandState.get().equals(CommandState.UNSUBSCRIBED)) {
                    return Observable.never();
                }
                // 使用 hystrix 包裹 command 执行
                return applyHystrixSemantics(_cmd);
            }
        };

        final Func1<R, R> wrapWithAllOnNextHooks = new Func1<R, R>() {
            @Override
            public R call(R r) {
                R afterFirstApplication = r;

                try {
                    afterFirstApplication = executionHook.onComplete(_cmd, r);
                } catch (Throwable hookEx) {
                    logger.warn("Error calling HystrixCommandExecutionHook.onComplete", hookEx);
                }

                try {
                    return executionHook.onEmit(_cmd, afterFirstApplication);
                } catch (Throwable hookEx) {
                    logger.warn("Error calling HystrixCommandExecutionHook.onEmit", hookEx);
                    return afterFirstApplication;
                }
            }
        };

        /**
         * 当任务完成时 触发的 钩子 调用 hook.success 方法
         */
        final Action0 fireOnCompletedHook = new Action0() {
            @Override
            public void call() {
                try {
                    executionHook.onSuccess(_cmd);
                } catch (Throwable hookEx) {
                    logger.warn("Error calling HystrixCommandExecutionHook.onSuccess", hookEx);
                }
            }
        };

        /**
         * Observable 工厂对象 上面的 函数对象先不看 针对 toObservable()方法 每个订阅者 会获取到一个 全新的 observable 对象
         */
        return Observable.defer(new Func0<Observable<R>>() {

            /**
             * 生成返回对象的 工厂
             * @return
             */
            @Override
            public Observable<R> call() {
                /* this is a stateful object so can only be used once */
                // 尝试构造 observable chain 失败时 直接抛出异常 BadRequest 是 不会进行回退的
                if (!commandState.compareAndSet(CommandState.NOT_STARTED, CommandState.OBSERVABLE_CHAIN_CREATED)) {
                    IllegalStateException ex = new IllegalStateException("This instance can only be executed once. Please instantiate a new instance.");
                    //TODO make a new error type for this
                    throw new HystrixRuntimeException(FailureType.BAD_REQUEST_EXCEPTION, _cmd.getClass(), getLogMessagePrefix() + " command executed multiple times - this is not permitted.", ex, null);
                }

                commandStartTimestamp = System.currentTimeMillis();

                // 默认情况下 会为每个请求 记录日志
                if (properties.requestLogEnabled().get()) {
                    // log this command execution regardless of what happened
                    if (currentRequestLog != null) {
                        // 为请求日志对象 添加 command (内部维护了 一个 队列 只允许存储多少个任务)
                        currentRequestLog.addExecutedCommand(_cmd);
                    }
                }

                // 判断是否为 req 对象做缓存  默认的 缓存键为 null  一般 对应 用户信息 可以尝试使用 用户id 作为缓存键
                final boolean requestCacheEnabled = isRequestCachingEnabled();
                // 获取缓存键对象
                final String cacheKey = getCacheKey();

                /* try from cache first */
                // 看来如果尝试从缓存中获取 是不会考虑是否被熔断的
                if (requestCacheEnabled) {
                    // 尝试使用缓存键 获取 响应结果  使用相同的 commandKey 时 生成的 requestCache是同一个对象
                    HystrixCommandResponseFromCache<R> fromCache = (HystrixCommandResponseFromCache<R>) requestCache.get(cacheKey);
                    if (fromCache != null) {
                        // 存在缓存 代表要从缓存中返回结果
                        isResponseFromCache = true;
                        // 处理从缓存中 获取的结果
                        return handleRequestCacheHitAndEmitValues(fromCache, _cmd);
                    }
                }

                // 当没有使用缓存的时候 开始正常的执行command
                Observable<R> hystrixObservable =
                        // 每次发射的 元素 通过调用 applyHystrixSemantics 使得执行command 被 hystrix 包裹  这里返回的 observable 可能是发射error 的数据流
                        Observable.defer(applyHystrixSemantics)
                                // 对该对象 的行为做封装 每次调用都会触发 hook.onComplete  和 onEmit
                                .map(wrapWithAllOnNextHooks);

                Observable<R> afterCache;

                // put in cache
                // 如果允许使用缓存 这里要更新缓存对象
                if (requestCacheEnabled && cacheKey != null) {
                    // wrap it for caching
                    // 将 本次 command 作为一个 缓存对象 下次执行命令 如果有携带缓存键 那么执行的时候 就会使用本次 command 的数据 作为下次 的结果
                    HystrixCachedObservable<R> toCache = HystrixCachedObservable.from(hystrixObservable, _cmd);
                    // 2个 使用相同缓存键的 command 可以 使用 cache 特性 查询到一样的数据结果
                    HystrixCommandResponseFromCache<R> fromCache = (HystrixCommandResponseFromCache<R>) requestCache.putIfAbsent(cacheKey, toCache);
                    // 代表其他线程 已经缓存了 该 commandKey 对应的结果
                    if (fromCache != null) {
                        // another thread beat us so we'll use the cached value instead
                        // 取消之前的数据订阅
                        toCache.unsubscribe();
                        // 代表 结果 从其他缓存中获取
                        isResponseFromCache = true;
                        // 使用 其他线程 抢占 并缓存的 数据结果
                        return handleRequestCacheHitAndEmitValues(fromCache, _cmd);
                    } else {
                        // we just created an ObservableCommand so we cast and return it
                        // 返回缓存数据流 作为 被处理的 目标数据流
                        afterCache = toCache.toObservable();
                    }
                } else {
                    // 不允许的情况下 直接获取结果
                    afterCache = hystrixObservable;
                }

                return afterCache
                        // 设置终止时的清理对象  也就是清除定时任务 并统计结果数据
                        .doOnTerminate(terminateCommandCleanup)     // perform cleanup once (either on normal terminal state (this line), or unsubscribe (next line))
                        // 取消订阅时 统计数据
                        .doOnUnsubscribe(unsubscribeCommandCleanup) // perform cleanup once
                        // 完成时 触发
                        .doOnCompleted(fireOnCompletedHook);
            }
        });
    }

    /**
     * 应用hystrix语义
     * @param _cmd
     * @return
     */
    private Observable<R> applyHystrixSemantics(final AbstractCommand<R> _cmd) {
        // mark that we're starting execution on the ExecutionHook
        // if this hook throws an exception, then a fast-fail occurs with no fallback.  No state is left inconsistent
        executionHook.onStart(_cmd);

        /* determine if we're allowed to execute */
        // 通过熔断器判断是否允许当前命令执行  刚创建 熔断器时 默认就是不拦截的
        if (circuitBreaker.attemptExecution()) {
            // 生成本command 对应的 信号量对象  如果使用的隔离策略是 信号量 就会限制 执行次数  因为每个 command 是共享信号量的
            final TryableSemaphore executionSemaphore = getExecutionSemaphore();
            // 默认情况下没有释放信号量
            final AtomicBoolean semaphoreHasBeenReleased = new AtomicBoolean(false);
            // 创建一个 用于释放信号量的 函数
            final Action0 singleSemaphoreRelease = new Action0() {
                @Override
                public void call() {
                    if (semaphoreHasBeenReleased.compareAndSet(false, true)) {
                        executionSemaphore.release();
                    }
                }
            };

            // 当抛出异常时 执行的函数 将异常情况 标记到 事件监听器上
            final Action1<Throwable> markExceptionThrown = new Action1<Throwable>() {
                @Override
                public void call(Throwable t) {
                    eventNotifier.markEvent(HystrixEventType.EXCEPTION_THROWN, commandKey);
                }
            };

            // 尝试获取门票  如果是没有 设置信号量的情况 这里不会做任何处理 实现是 noop
            if (executionSemaphore.tryAcquire()) {
                try {
                    /* used to track userThreadExecutionTime */
                    // 设置 任务启动时间
                    executionResult = executionResult.setInvocationStartTime(System.currentTimeMillis());
                    // 开始执行任务
                    return executeCommandAndObserve(_cmd)
                            .doOnError(markExceptionThrown)
                            .doOnTerminate(singleSemaphoreRelease)
                            .doOnUnsubscribe(singleSemaphoreRelease);
                } catch (RuntimeException e) {
                    // 调用该方法会触发 Observer.onError
                    return Observable.error(e);
                }

                // 下面2种异常处理手段实际上都会 调用 fallback 方法 fallback本身执行失败 或者因某些原因 执行失败 还是会向下游抛出异常
            } else {
                // 触发信号量 拒绝函数
                return handleSemaphoreRejectionViaFallback();
            }
        } else {
            // 代表被熔断
            return handleShortCircuitViaFallback();
        }
    }

    /**
     * 是否需要统计command 的执行数据
     * @return
     */
    abstract protected boolean commandIsScalar();

    /**
     * This decorates "Hystrix" functionality around the run() Observable.
     * 将 command 方法  (也就是用户代码) 使用 hystrix 进行包装
     * @return R
     */
    private Observable<R> executeCommandAndObserve(final AbstractCommand<R> _cmd) {
        // 获取当前线程的 上下文对象
        final HystrixRequestContext currentRequestContext = HystrixRequestContext.getContextForCurrentThread();

        // 标记 发射事件
        final Action1<R> markEmits = new Action1<R>() {
            @Override
            public void call(R r) {
                // 子类实现 是否需要向下游发射事件  默认为false
                if (shouldOutputOnNextEvents()) {
                    // 将 数据记录到 eventCount 中
                    executionResult = executionResult.addEvent(HystrixEventType.EMIT);
                    eventNotifier.markEvent(HystrixEventType.EMIT, commandKey);
                }
                // command 是否是 标量??? 是的话记录SUCCESS 数据
                if (commandIsScalar()) {
                    long latency = System.currentTimeMillis() - executionResult.getStartTimestamp();
                    eventNotifier.markEvent(HystrixEventType.SUCCESS, commandKey);
                    executionResult = executionResult.addEvent((int) latency, HystrixEventType.SUCCESS);
                    // 标记 command 已经执行   getOrderedList 会按照 枚举的顺序 重新排列 内部记录的数据
                    eventNotifier.markCommandExecution(getCommandKey(), properties.executionIsolationStrategy().get(), (int) latency, executionResult.getOrderedList());
                    // 如果 熔断器 处于 半开状态 关闭它
                    circuitBreaker.markSuccess();
                }
            }
        };

        // 当 command 执行完成时 触发
        final Action0 markOnCompleted = new Action0() {
            @Override
            public void call() {
                // 看来只有设置该标识时 才会 统计某些数据
                if (!commandIsScalar()) {
                    long latency = System.currentTimeMillis() - executionResult.getStartTimestamp();
                    eventNotifier.markEvent(HystrixEventType.SUCCESS, commandKey);
                    executionResult = executionResult.addEvent((int) latency, HystrixEventType.SUCCESS);
                    eventNotifier.markCommandExecution(getCommandKey(), properties.executionIsolationStrategy().get(), (int) latency, executionResult.getOrderedList());
                    circuitBreaker.markSuccess();
                }
            }
        };

        // 当处理 回退方法时 触发
        final Func1<Throwable, Observable<R>> handleFallback = new Func1<Throwable, Observable<R>>() {
            @Override
            public Observable<R> call(Throwable t) {
                // 因为 发起了 回退 所以当前操作失败的概率 很高就 打开熔断器
                circuitBreaker.markNonSuccess();
                // 将抛出的Throwable 封装成 Exception
                Exception e = getExceptionFromThrowable(t);
                executionResult = executionResult.setExecutionException(e);
                // 拒绝执行时 走特殊的处理逻辑  该异常是由  ThreadPool 抛出的 这里会根据是否 设置回退逻辑 进行重试
                if (e instanceof RejectedExecutionException) {
                    return handleThreadPoolRejectionViaFallback(e);
                // 如果是 hystrix超时的异常
                } else if (t instanceof HystrixTimeoutException) {
                    return handleTimeoutViaFallback();
                // 如果 是 badRequest 这里不会进行回退
                } else if (t instanceof HystrixBadRequestException) {
                    return handleBadRequestByEmittingError(e);
                } else {
                    /*
                     * Treat HystrixBadRequestException from ExecutionHook like a plain HystrixBadRequestException.
                     * 如果是 HystrixBadRequestException 的子类 也是直接抛出异常
                     */
                    if (e instanceof HystrixBadRequestException) {
                        eventNotifier.markEvent(HystrixEventType.BAD_REQUEST, commandKey);
                        return Observable.error(e);
                    }

                    // 进行回退 or 抛出异常 (如果是 不可恢复的error 对象就不重试)
                    return handleFailureViaFallback(e);
                }
            }
        };

        /**
         * 设置请求上下文
         */
        final Action1<Notification<? super R>> setRequestContext = new Action1<Notification<? super R>>() {
            @Override
            public void call(Notification<? super R> rNotification) {
                // 如果执行线程中没有设置上下文 就将 command 设置的上下文对象添加到 执行线程中
                setRequestContextIfNeeded(currentRequestContext);
            }
        };

        Observable<R> execution;

        // 上面都是 初始化一些函数对象 下面才开始执行任务

        // 判断是否允许 超时  这里执行command 要看是否进行隔离
        if (properties.executionTimeoutEnabled().get()) {
            // 允许超时的话  将 command 隔离后 使用TimeoutOperator 进行代理
            // executeCommandWithSpecifiedIsolation 使用  thread  或者信号量进行隔离 之后返回 用户自定义的 Observable 对象
            execution = executeCommandWithSpecifiedIsolation(_cmd)
                    .lift(new HystrixObservableTimeoutOperator<R>(_cmd));
        } else {
            execution = executeCommandWithSpecifiedIsolation(_cmd);
        }

        // onNext 事件  将事件 发送到下游 以及 记录数据
        return execution.doOnNext(markEmits)
                // 设置了 需要统计数据的场景下 将 complete的数据 设置到 统计对象中
                .doOnCompleted(markOnCompleted)
                // 遇到异常时  不走 onError 而是尝试走这个方法  也就是判断异常是否 有回退的可能 有的话 就进行回退 否则抛出异常
                .onErrorResumeNext(handleFallback)
                // 针对每个请求 都要切换上下文  因为 一旦切换到其他线程就会丢失请求信息
                .doOnEach(setRequestContext);
    }

    /**
     * 使用特殊的隔离策略 来执行command
     * @param _cmd
     * @return
     */
    private Observable<R> executeCommandWithSpecifiedIsolation(final AbstractCommand<R> _cmd) {
        // 如果使用 thread 作为线程隔离策略  信号量隔离 是在外层起作用 就是同一个command 不能在短时间内 执行超过信号量的次数
        if (properties.executionIsolationStrategy().get() == ExecutionIsolationStrategy.THREAD) {
            // mark that we are executing in a thread (even if we end up being rejected we still were a THREAD execution and not SEMAPHORE)
            // 这里没有直接返回结果 而是一个 observable 对象 每个订阅者都会拿到一个新对象 (都走一遍下面的逻辑)
            return Observable.defer(new Func0<Observable<R>>() {
                @Override
                public Observable<R> call() {
                    // 设置开始执行
                    executionResult = executionResult.setExecutionOccurred();
                    // 将state 从 chain_created 修改成 user_code_executed
                    if (!commandState.compareAndSet(CommandState.OBSERVABLE_CHAIN_CREATED, CommandState.USER_CODE_EXECUTED)) {
                        return Observable.error(new IllegalStateException("execution attempted while in state : " + commandState.get().name()));
                    }

                    // 标记开始统计数据 并传入对应的 隔离策略  这里会发送事件流 到下游 这样相关的统计对象只要订阅了 对应对象的 subject 就可以 收集到数据
                    // 这里 还会增加一个 并发数  在执行完 command 时 会根据 是否执行了 user代表 减少并发数 这个并发数 无论是否使用 Thread 做隔离 都需要记录
                    metrics.markCommandStart(commandKey, threadPoolKey, ExecutionIsolationStrategy.THREAD);

                    // 如果当前超时 不允许正常执行
                    if (isCommandTimedOut.get() == TimedOutStatus.TIMED_OUT) {
                        // the command timed out in the wrapping thread so we will return immediately
                        // and not increment any of the counters below or other such logic
                        // 抛出异常 之后应该会开启重试策略
                        return Observable.error(new RuntimeException("timed out before executing run()"));
                    }
                    // 将线程状态 修改为 启用
                    if (threadState.compareAndSet(ThreadState.NOT_USING_THREAD, ThreadState.STARTED)) {
                        //we have not been unsubscribed, so should proceed
                        // 增加 hystrix 全局线程计数器
                        HystrixCounters.incrementGlobalConcurrentThreads();
                        // 标记 执行了某条线程  只是针对 该 ThreadPool 对象 增加了计数器的值
                        threadPool.markThreadExecution();
                        // store the command that is being run
                        // 将本次 command 弹入一个 绑定本线程的 栈对象 并返回一个 出栈方法  一般是在本次command 执行完成后 弹出
                        endCurrentThreadExecutingCommand = Hystrix.startCurrentThreadExecutingCommand(getCommandKey());
                        // 设置result 对象 本次 在线程中执行
                        executionResult = executionResult.setExecutedInThread();
                        /**
                         * If any of these hooks throw an exception, then it appears as if the actual execution threw an error
                         */
                        try {
                            // 执行对应的 生命周期函数
                            executionHook.onThreadStart(_cmd);
                            executionHook.onRunStart(_cmd);
                            executionHook.onExecutionStart(_cmd);
                            // 将 用户的 run() 包装成 observable 对象
                            return getUserExecutionObservable(_cmd);
                        } catch (Throwable ex) {
                            // 出现异常 情况 抛出异常
                            return Observable.error(ex);
                        }
                    } else {
                        //command has already been unsubscribed, so return immediately
                        // 代表 已经开始执行 这里直接返回 空对象
                        return Observable.empty();
                    }
                }
            // 当触发 onComplete 或者 onError 时 会触发该方法
            }).doOnTerminate(new Action0() {
                @Override
                public void call() {
                    // 代表 本次执行结束
                    if (threadState.compareAndSet(ThreadState.STARTED, ThreadState.TERMINAL)) {
                        // 处理完成任务的逻辑
                        handleThreadEnd(_cmd);
                    }
                    if (threadState.compareAndSet(ThreadState.NOT_USING_THREAD, ThreadState.TERMINAL)) {
                        //if it was never started and received terminal, then no need to clean up (I don't think this is possible)
                    }
                    //if it was unsubscribed, then other cleanup handled it
                }
            // 取消订阅时 触发
            }).doOnUnsubscribe(new Action0() {
                @Override
                public void call() {
                    // 如果触发了 取消订阅 也会减少统计对象中的 并发数
                    if (threadState.compareAndSet(ThreadState.STARTED, ThreadState.UNSUBSCRIBED)) {
                        handleThreadEnd(_cmd);
                    }
                    if (threadState.compareAndSet(ThreadState.NOT_USING_THREAD, ThreadState.UNSUBSCRIBED)) {
                        //if it was never started and was cancelled, then no need to clean up
                    }
                    //if it was terminal, then other cleanup handled it
                }
            // 在这里 使用 Schduler 实现了 线程隔离
            }).subscribeOn(threadPool.getScheduler(new Func0<Boolean>() {
                @Override
                public Boolean call() {
                    // 使用线程隔离的情况下 超时是否中断线程
                    return properties.executionIsolationThreadInterruptOnTimeout().get() && _cmd.isCommandTimedOut.get() == TimedOutStatus.TIMED_OUT;
                }
            }));
        } else {
            return Observable.defer(new Func0<Observable<R>>() {
                @Override
                public Observable<R> call() {
                    // 代表开始执行
                    executionResult = executionResult.setExecutionOccurred();
                    if (!commandState.compareAndSet(CommandState.OBSERVABLE_CHAIN_CREATED, CommandState.USER_CODE_EXECUTED)) {
                        return Observable.error(new IllegalStateException("execution attempted while in state : " + commandState.get().name()));
                    }

                    // 使用信号量进行统计
                    metrics.markCommandStart(commandKey, threadPoolKey, ExecutionIsolationStrategy.SEMAPHORE);
                    // semaphore isolated
                    // store the command that is being run
                    endCurrentThreadExecutingCommand = Hystrix.startCurrentThreadExecutingCommand(getCommandKey());
                    try {
                        executionHook.onRunStart(_cmd);
                        executionHook.onExecutionStart(_cmd);
                        // 包装用户返回的 observable
                        return getUserExecutionObservable(_cmd);  //the getUserExecutionObservable method already wraps sync exceptions, so this shouldn't throw
                    } catch (Throwable ex) {
                        //If the above hooks throw, then use that as the result of the run method
                        return Observable.error(ex);
                    }
                }
            });
        }
    }

    /**
     * Execute <code>getFallback()</code> within protection of a semaphore that limits number of concurrent executions.
     * <p>
     * Fallback implementations shouldn't perform anything that can be blocking, but we protect against it anyways in case someone doesn't abide by the contract.
     * <p>
     * If something in the <code>getFallback()</code> implementation is latent (such as a network call) then the semaphore will cause us to start rejecting requests rather than allowing potentially
     * all threads to pile up and block.
     *
     * @return K
     * @throws UnsupportedOperationException
     *             if getFallback() not implemented
     * @throws HystrixRuntimeException
     *             if getFallback() fails (throws an Exception) or is rejected by the semaphore
     *             应该是将传入的信息封装成 一个 Hystrix 异常对象并 发射到下游
     *             这里即使observable 对象 会 弹出异常 之后的 处理对象 可能还是会以某种方式 继续进行重试
     */
    private Observable<R> getFallbackOrThrowException(final AbstractCommand<R> _cmd, final HystrixEventType eventType, final FailureType failureType, final String message, final Exception originalException) {
        // 获取上下文对象
        final HystrixRequestContext requestContext = HystrixRequestContext.getContextForCurrentThread();
        // 获取调用时长
        long latency = System.currentTimeMillis() - executionResult.getStartTimestamp();
        // record the executionResult
        // do this before executing fallback so it can be queried from within getFallback (see See https://github.com/Netflix/Hystrix/pull/144)
        // 设置异常结果 和 执行时长 返回新的result 对象
        executionResult = executionResult.addEvent((int) latency, eventType);

        // 判断能否从该异常对象中恢复  比如针对获取信号量门票失败的情况下 可以考虑从异常中恢复 (通过延时调用)
        if (isUnrecoverable(originalException)) {
            // 代表出现了不可以恢复的异常所以无法fallback
            logger.error("Unrecoverable Error for HystrixCommand so will throw HystrixRuntimeException and not apply fallback. ", originalException);

            /* executionHook for all errors */
            // 将异常对象 包装并设置钩子  如果中途发生了异常会将 e 原样返回
            Exception e = wrapWithOnErrorHook(failureType, originalException);
            // 将异常信息封装成 hystrix 异常对象 并发射到下游
            return Observable.error(new HystrixRuntimeException(failureType, this.getClass(), getLogMessagePrefix() + " " + message + " and encountered unrecoverable error.", e, null));
        } else {
            // 如果是可恢复的 Error 尝试 使用hystrix 的回退机制
            if (isRecoverableError(originalException)) {
                logger.warn("Recovered from java.lang.Error by serving Hystrix fallback", originalException);
            }

            // 判断是否开启了回退机制
            if (properties.fallbackEnabled().get()) {
                /* fallback behavior is permitted so attempt */

                // 设置 上下文对象的 函数 (如果执行任务的 线程没有设置上下文 就将command 所在的线程上下文设置进来)
                final Action1<Notification<? super R>> setRequestContext = new Action1<Notification<? super R>>() {
                    @Override
                    public void call(Notification<? super R> rNotification) {
                        setRequestContextIfNeeded(requestContext);
                    }
                };

                // 处理回退方法 时收到 发射的元素
                final Action1<R> markFallbackEmit = new Action1<R>() {
                    @Override
                    public void call(R r) {
                        // 是否 要 输出到下面的event 中
                        if (shouldOutputOnNextEvents()) {
                            executionResult = executionResult.addEvent(HystrixEventType.FALLBACK_EMIT);
                            // 标记成 回退发射
                            eventNotifier.markEvent(HystrixEventType.FALLBACK_EMIT, commandKey);
                        }
                    }
                };

                // 标记执行回退完成
                final Action0 markFallbackCompleted = new Action0() {
                    @Override
                    public void call() {
                        // 获取本次任务执行时间
                        long latency = System.currentTimeMillis() - executionResult.getStartTimestamp();
                        // 标记回退成功事件
                        eventNotifier.markEvent(HystrixEventType.FALLBACK_SUCCESS, commandKey);
                        executionResult = executionResult.addEvent((int) latency, HystrixEventType.FALLBACK_SUCCESS);
                    }
                };

                // 处理回退异常  当 接收到 onError后 会触发该方法  如果用户没有设置回退方法 就会返回一个 直接发射 error 的 observable 对象 也就会 触发这里的逻辑
                final Func1<Throwable, Observable<R>> handleFallbackError = new Func1<Throwable, Observable<R>>() {
                    @Override
                    public Observable<R> call(Throwable t) {
                        /* executionHook for all errors */
                        // 包装原始异常
                        Exception e = wrapWithOnErrorHook(failureType, originalException);
                        // 将Throwable 包装成Exception
                        Exception fe = getExceptionFromThrowable(t);

                        long latency = System.currentTimeMillis() - executionResult.getStartTimestamp();
                        Exception toEmit;

                        // 没有设置回退方法的情况下 就会返回UnsupportedOperationException 代表 该command 本身就不支持回退
                        if (fe instanceof UnsupportedOperationException) {
                            logger.debug("No fallback for HystrixCommand. ", fe); // debug only since we're throwing the exception and someone higher will do something with it
                            eventNotifier.markEvent(HystrixEventType.FALLBACK_MISSING, commandKey);
                            executionResult = executionResult.addEvent((int) latency, HystrixEventType.FALLBACK_MISSING);

                            toEmit = new HystrixRuntimeException(failureType, _cmd.getClass(), getLogMessagePrefix() + " " + message + " and no fallback available.", e, fe);
                        } else {
                            // 如果执行 回退方法失败的情况
                            logger.debug("HystrixCommand execution " + failureType.name() + " and fallback failed.", fe);
                            // 标记回退失败
                            eventNotifier.markEvent(HystrixEventType.FALLBACK_FAILURE, commandKey);
                            executionResult = executionResult.addEvent((int) latency, HystrixEventType.FALLBACK_FAILURE);

                            toEmit = new HystrixRuntimeException(failureType, _cmd.getClass(), getLogMessagePrefix() + " " + message + " and fallback failed.", e, fe);
                        }

                        // NOTE: we're suppressing fallback exception here
                        // 如果是不需要被包装的异常 发射原始异常到下游
                        if (shouldNotBeWrapped(originalException)) {
                            return Observable.error(e);
                        }

                        // 将包装异常 发射到下游
                        return Observable.error(toEmit);
                    }
                };

                // 获取 回退使用的信号量 看来回退 也是有数量限制的  如果 获取 回退的信号量 也失败 可能要等待一段时间  该信号量 是一定会设置的  而执行是否使用信号量是 根据隔离策略
                final TryableSemaphore fallbackSemaphore = getFallbackSemaphore();
                // 默认情况下 该信号量还没有被释放
                final AtomicBoolean semaphoreHasBeenReleased = new AtomicBoolean(false);
                // 释放信号量的函数
                final Action0 singleSemaphoreRelease = new Action0() {
                    @Override
                    public void call() {
                        if (semaphoreHasBeenReleased.compareAndSet(false, true)) {
                            fallbackSemaphore.release();
                        }
                    }
                };

                // 回退执行链
                Observable<R> fallbackExecutionChain;

                // acquire a permit
                // 上面只是 创建执行回退需要的 函数 这里才是真正执行回退的逻辑  如果 没有获取到 回退的信号量 会触发下游的 onError 不过这是默认情况
                if (fallbackSemaphore.tryAcquire()) {
                    try {
                        // 判断用户是否设置了自定义的回退方法  如果用户没有设置回退方法 返回的 fallbackExecutionChain 是一个会直接发射异常的 Observable 对象
                        if (isFallbackUserDefined()) {
                            // 代表 开始执行回退
                            executionHook.onFallbackStart(this);
                            // 该方法会 将command 实现类的  回退方法 返回结果 用 observable 包裹起来
                            fallbackExecutionChain = getFallbackObservable();
                        } else {
                            //same logic as above without the hook invocation
                            // 同样包装回退方法
                            fallbackExecutionChain = getFallbackObservable();
                        }
                    } catch (Throwable ex) {
                        //If hook or user-fallback throws, then use that as the result of the fallback lookup
                        // 如果出现了异常 将 异常对象作为本次 回退 得到的结果
                        fallbackExecutionChain = Observable.error(ex);
                    }

                    return fallbackExecutionChain
                            // 当调用 任何 环节时先触发该方法  如果执行该任务的线程 还没有设置上下文对象 就使用 command 的入口线程的上下文
                            .doOnEach(setRequestContext)
                            // lift 相当于是  代理函数 将 传入的 subscribe 转换成新的 订阅对象 再触发 事件源的 onSubscribe 方法
                            // 在一个 原始的 Observable 对象调用 lift(Operator) 后 会返回一个新的 Observable 对象 该对象内部的 整个订阅 逻辑以及替换成一个代理过后的对象了
                            // 该lift 方法 只是在调用对应的 方法时 额外调用了 钩子方法
                            .lift(new FallbackHookApplication(_cmd))
                            // 也是执行 hook相关
                            .lift(new DeprecatedOnFallbackHookApplication(_cmd))
                            // onNext 时 触发 将数据 累加到 result 中
                            .doOnNext(markFallbackEmit)
                            // onCompleted 时 触发 将event 添加到 result 中(就是添加到 eventCount 中)  同时触发相关 hook
                            .doOnCompleted(markFallbackCompleted)
                            // 异常恢复  如果遇到了Error 会尝试调用该方法  内部会调用 lift() 方法 并传入
                            .onErrorResumeNext(handleFallbackError)
                            // 结束时 释放 信号量
                            .doOnTerminate(singleSemaphoreRelease)
                            // 取消订阅时 释放信号量
                            .doOnUnsubscribe(singleSemaphoreRelease);
                } else {
                    // 如果进行回退时 没有获取到信号量 会触发下游的 onError
                    return handleFallbackRejectionByEmittingError();
                }
            } else {
                // 禁止开启 回退 时 发射执行的异常
                return handleFallbackDisabledByEmittingError(originalException, failureType, message);
            }
        }
    }

    /**
     * 获取可观察对象
     * @param _cmd
     * @return
     */
    private Observable<R> getUserExecutionObservable(final AbstractCommand<R> _cmd) {
        Observable<R> userObservable;

        try {
            // 子类实现  应该是获取用户自定义的 observable 对象
            userObservable = getExecutionObservable();
        } catch (Throwable ex) {
            // the run() method is a user provided implementation so can throw instead of using Observable.onError
            // so we catch it here and turn it into Observable.error
            userObservable = Observable.error(ex);
        }

        // 加工用户返回的 observable 对象 在 执行 对应的函数时 会触发 hook 的相关方法
        return userObservable
                .lift(new ExecutionHookApplication(_cmd))
                .lift(new DeprecatedOnRunHookApplication(_cmd));
    }

    /**
     * 从缓存中获取结果对象
     * @param fromCache  缓存命中时返回的结果
     * @param _cmd  本次执行的command 实例对象
     * @return
     */
    private Observable<R> handleRequestCacheHitAndEmitValues(final HystrixCommandResponseFromCache<R> fromCache, final AbstractCommand<R> _cmd) {
        try {
            // 触发 缓存命中方法  默认空钩子 不做任何处理
            executionHook.onCacheHit(this);
        } catch (Throwable hookEx) {
            logger.warn("Error calling HystrixCommandExecutionHook.onCacheHit", hookEx);
        }

        // 应该是将 缓存中的数据 转移到 本 command 中
        return fromCache.toObservableWithStateCopiedInto(this)
                // 终结时 触发 (就是 触发 complete 或者 error)
                .doOnTerminate(new Action0() {
                    @Override
                    public void call() {
                        // 根据是否执行了 用户代码 执行对应的  clean 逻辑
                        if (commandState.compareAndSet(CommandState.OBSERVABLE_CHAIN_CREATED, CommandState.TERMINAL)) {
                            cleanUpAfterResponseFromCache(false); //user code never ran
                        } else if (commandState.compareAndSet(CommandState.USER_CODE_EXECUTED, CommandState.TERMINAL)) {
                            cleanUpAfterResponseFromCache(true); //user code did run
                        }
                    }
                })
                // 当 缓存对象被取消订阅时
                .doOnUnsubscribe(new Action0() {
                    @Override
                    public void call() {
                        // 取消订阅时 也做清理逻辑
                        if (commandState.compareAndSet(CommandState.OBSERVABLE_CHAIN_CREATED, CommandState.UNSUBSCRIBED)) {
                            cleanUpAfterResponseFromCache(false); //user code never ran
                        } else if (commandState.compareAndSet(CommandState.USER_CODE_EXECUTED, CommandState.UNSUBSCRIBED)) {
                            cleanUpAfterResponseFromCache(true); //user code did run
                        }
                    }
                });
    }

    /**
     * 当使用缓存时 任务结束 的清理工作
     * @param commandExecutionStarted
     */
    private void cleanUpAfterResponseFromCache(boolean commandExecutionStarted) {
        // 获取 定时器 监听对象
        Reference<TimerListener> tl = timeoutTimer.get();
        // 清除引用
        if (tl != null) {
            tl.clear();
        }

        // 代表本次 command 的执行时间
        final long latency = System.currentTimeMillis() - commandStartTimestamp;
        // 以下的流式操作类似于 builder  就是加工 result对象
        executionResult = executionResult
                // 从缓存中获取 延迟 就是-1
                .addEvent(-1, HystrixEventType.RESPONSE_FROM_CACHE)
                .markUserThreadCompletion(latency)
                .setNotExecutedInThread();
        // 该对象是 针对使用缓存的情况 作为统计数据用的
        ExecutionResult cacheOnlyForMetrics = ExecutionResult.from(HystrixEventType.RESPONSE_FROM_CACHE)
                .markUserThreadCompletion(latency);
        // 将本次结果 发射到数据流中
        metrics.markCommandDone(cacheOnlyForMetrics, commandKey, threadPoolKey, commandExecutionStarted);
        // 通知监听器 本次 command 执行从缓存中获取
        eventNotifier.markEvent(HystrixEventType.RESPONSE_FROM_CACHE, commandKey);
    }

    /**
     * 代表本次 command 处理完成
     * @param commandExecutionStarted  代表是否执行过用户代码
     */
    private void handleCommandEnd(boolean commandExecutionStarted) {
        Reference<TimerListener> tl = timeoutTimer.get();
        if (tl != null) {
            tl.clear();
        }

        // 获取本次执行时间
        long userThreadLatency = System.currentTimeMillis() - commandStartTimestamp;
        executionResult = executionResult.markUserThreadCompletion((int) userThreadLatency);
        // 如果 关闭的 结果对象为 null  将 executionResultAtTimeOfCancellation 下发到下游
        if (executionResultAtTimeOfCancellation == null) {
            // 标记 任务被完成  生成CommandComplete 对象 并发射到下游
            metrics.markCommandDone(executionResult, commandKey, threadPoolKey, commandExecutionStarted);
        } else {
            metrics.markCommandDone(executionResultAtTimeOfCancellation, commandKey, threadPoolKey, commandExecutionStarted);
        }

        // 如果 存在command 执行完成后的 回调对象 就调用
        if (endCurrentThreadExecutingCommand != null) {
            endCurrentThreadExecutingCommand.call();
        }
    }

    /**
     * 当尝试获取信号量 没有获取到门票时 触发
     * @return
     */
    private Observable<R> handleSemaphoreRejectionViaFallback() {
        Exception semaphoreRejectionException = new RuntimeException("could not acquire a semaphore for execution");
        // 将 异常设置到 result 对象中
        executionResult = executionResult.setExecutionException(semaphoreRejectionException);
        // 触发监听器
        eventNotifier.markEvent(HystrixEventType.SEMAPHORE_REJECTED, commandKey);
        logger.debug("HystrixCommand Execution Rejection by Semaphore."); // debug only since we're throwing the exception and someone higher will do something with it
        // retrieve a fallback or throw an exception if no fallback available
        // 根据策略选择 回退 或者是 抛出异常 回退代表获取信号量失败时 没有直接 终止调用 而是 等待一段时间后开始重试
        return getFallbackOrThrowException(this, HystrixEventType.SEMAPHORE_REJECTED, FailureType.REJECTED_SEMAPHORE_EXECUTION,
                "could not acquire a semaphore for execution", semaphoreRejectionException);
    }

    /**
     * 代表当前请求被 熔断
     * @return
     */
    private Observable<R> handleShortCircuitViaFallback() {
        // record that we are returning a short-circuited fallback
        // 标记 熔断事件
        eventNotifier.markEvent(HystrixEventType.SHORT_CIRCUITED, commandKey);
        // short-circuit and go directly to fallback (or throw an exception if no fallback implemented)
        // 该异常 在之后是能重试的
        Exception shortCircuitException = new RuntimeException("Hystrix circuit short-circuited and is OPEN");
        // 设置异常结果
        executionResult = executionResult.setExecutionException(shortCircuitException);
        try {
            // 根据情况 选择 回退 还是 抛出异常  因为 本command 也许不支持 回退逻辑
            return getFallbackOrThrowException(this, HystrixEventType.SHORT_CIRCUITED, FailureType.SHORTCIRCUIT,
                    "short-circuited", shortCircuitException);
        } catch (Exception e) {
            // 如果 回退中出现了
            return Observable.error(e);
        }
    }

    /**
     * 处理线程池 拒绝异常
     * @param underlying
     * @return
     */
    private Observable<R> handleThreadPoolRejectionViaFallback(Exception underlying) {
        // 通知该事件
        eventNotifier.markEvent(HystrixEventType.THREAD_POOL_REJECTED, commandKey);
        // 统计相关数据
        threadPool.markThreadRejection();
        // use a fallback instead (or throw exception if not implemented)
        // 这里是 根据 异常对象 选择 抛出异常还是 回退 以便下次重试
        return getFallbackOrThrowException(this, HystrixEventType.THREAD_POOL_REJECTED, FailureType.REJECTED_THREAD_EXECUTION, "could not be queued for execution", underlying);
    }

    /**
     * 当出现  HystrixTimeout 时
     * @return
     */
    private Observable<R> handleTimeoutViaFallback() {
        // 这里会尝试进行重试
        return getFallbackOrThrowException(this, HystrixEventType.TIMEOUT, FailureType.TIMEOUT, "timed-out", new TimeoutException());
    }

    /**
     * 处理 badRequest
     * @param underlying
     * @return
     */
    private Observable<R> handleBadRequestByEmittingError(Exception underlying) {
        Exception toEmit = underlying;

        try {
            long executionLatency = System.currentTimeMillis() - executionResult.getStartTimestamp();
            eventNotifier.markEvent(HystrixEventType.BAD_REQUEST, commandKey);
            executionResult = executionResult.addEvent((int) executionLatency, HystrixEventType.BAD_REQUEST);
            // 使用钩子 处理异常
            Exception decorated = executionHook.onError(this, FailureType.BAD_REQUEST_EXCEPTION, underlying);

            if (decorated instanceof HystrixBadRequestException) {
                toEmit = decorated;
            } else {
                logger.warn("ExecutionHook.onError returned an exception that was not an instance of HystrixBadRequestException so will be ignored.", decorated);
            }
        } catch (Exception hookEx) {
            logger.warn("Error calling HystrixCommandExecutionHook.onError", hookEx);
        }
        /*
         * HystrixBadRequestException is treated differently and allowed to propagate without any stats tracking or fallback logic
         */
        return Observable.error(toEmit);
    }

    /**
     * 处理失败 情况 这里主要是 通知监听器 和设置 result 对象 并且根据异常信息来判断是回退还是抛出异常
     * @param underlying
     * @return
     */
    private Observable<R> handleFailureViaFallback(Exception underlying) {
        /**
         * All other error handling
         */
        logger.debug("Error executing HystrixCommand.run(). Proceeding to fallback logic ...", underlying);

        // report failure
        eventNotifier.markEvent(HystrixEventType.FAILURE, commandKey);

        // record the exception
        executionResult = executionResult.setException(underlying);
        return getFallbackOrThrowException(this, HystrixEventType.FAILURE, FailureType.COMMAND_EXCEPTION, "failed", underlying);
    }

    /**
     * 当回退时 被 隔离策略拒绝 体现在 没有 信号量 permit
     * @return
     */
    private Observable<R> handleFallbackRejectionByEmittingError() {
        long latencyWithFallback = System.currentTimeMillis() - executionResult.getStartTimestamp();
        // 触发监听器事件
        eventNotifier.markEvent(HystrixEventType.FALLBACK_REJECTION, commandKey);
        executionResult = executionResult.addEvent((int) latencyWithFallback, HystrixEventType.FALLBACK_REJECTION);
        logger.debug("HystrixCommand Fallback Rejection."); // debug only since we're throwing the exception and someone higher will do something with it
        // if we couldn't acquire a permit, we "fail fast" by throwing an exception
        // 发射一个包含 hystrix 的异常对象
        return Observable.error(new HystrixRuntimeException(FailureType.REJECTED_SEMAPHORE_FALLBACK, this.getClass(), getLogMessagePrefix() + " fallback execution rejected.", null, null));
    }

    /**
     * 当 配置中设置了 fallbackdisbaled  返回特定异常
     * @param underlying
     * @param failureType
     * @param message
     * @return
     */
    private Observable<R> handleFallbackDisabledByEmittingError(Exception underlying, FailureType failureType, String message) {
        /* fallback is disabled so throw HystrixRuntimeException */
        logger.debug("Fallback disabled for HystrixCommand so will throw HystrixRuntimeException. ", underlying); // debug only since we're throwing the exception and someone higher will do something with it
        // 通知 事件监听器
        eventNotifier.markEvent(HystrixEventType.FALLBACK_DISABLED, commandKey);

        /* executionHook for all errors */
        Exception wrapped = wrapWithOnErrorHook(failureType, underlying);
        // 将异常对象包装成 hystrix 异常对象后发射
        return Observable.error(new HystrixRuntimeException(failureType, this.getClass(), getLogMessagePrefix() + " " + message + " and fallback disabled.", wrapped, null));
    }

    protected boolean shouldNotBeWrapped(Throwable underlying) {
        return underlying instanceof ExceptionNotWrappedByHystrix;
    }

    /**
     * Returns true iff the t was caused by a java.lang.Error that is unrecoverable.  Note: not all java.lang.Errors are unrecoverable.
     * @see <a href="https://github.com/Netflix/Hystrix/issues/713"></a> for more context
     * Solution taken from <a href="https://github.com/ReactiveX/RxJava/issues/748"></a>
     *
     * The specific set of Error that are considered unrecoverable are:
     * <ul>
     * <li>{@code StackOverflowError}</li>
     * <li>{@code VirtualMachineError}</li>
     * <li>{@code ThreadDeath}</li>
     * <li>{@code LinkageError}</li>
     * </ul>
     *
     * @param t throwable to check
     * @return true iff the t was caused by a java.lang.Error that is unrecoverable
     * 判断异常是否不能恢复
     */
    private boolean isUnrecoverable(Throwable t) {
        if (t != null && t.getCause() != null) {
            Throwable cause = t.getCause();
            if (cause instanceof StackOverflowError) {
                return true;
            } else if (cause instanceof VirtualMachineError) {
                return true;
            } else if (cause instanceof ThreadDeath) {
                return true;
            } else if (cause instanceof LinkageError) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断是否是可恢复的 Error
     * @param t
     * @return
     */
    private boolean isRecoverableError(Throwable t) {
        if (t != null && t.getCause() != null) {
            Throwable cause = t.getCause();
            if (cause instanceof java.lang.Error) {
                return !isUnrecoverable(t);
            }
        }
        return false;
    }

    /**
     * 当线程执行完成时 触发
     * @param _cmd
     */
    protected void handleThreadEnd(AbstractCommand<R> _cmd) {
        // 减少全局的线程并发量
        HystrixCounters.decrementGlobalConcurrentThreads();
        // 减少本 ThreadPool 内部维护的线程数量
        threadPool.markThreadCompletion();
        try {
            // 触发钩子方法
            executionHook.onThreadComplete(_cmd);
        } catch (Throwable hookEx) {
            logger.warn("Error calling HystrixCommandExecutionHook.onThreadComplete", hookEx);
        }
    }

    /**
     *
     * @return if onNext events should be reported on
     * This affects {@link HystrixRequestLog}, and {@link HystrixEventNotifier} currently.
     * 默认情况下 不将事件发射到下游
     */
    protected boolean shouldOutputOnNextEvents() {
        return false;
    }

    /**
     * 使得 command 支持超时调用
     * @param <R>
     */
    private static class HystrixObservableTimeoutOperator<R> implements Operator<R, R> {

        final AbstractCommand<R> originalCommand;

        public HystrixObservableTimeoutOperator(final AbstractCommand<R> originalCommand) {
            this.originalCommand = originalCommand;
        }

        // 包装订阅者对象
        @Override
        public Subscriber<? super R> call(final Subscriber<? super R> child) {
            // 代表 一组 订阅者对象
            final CompositeSubscription s = new CompositeSubscription();
            // if the child unsubscribes we unsubscribe our parent as well
            // 每个 subscription 对象中包含一个 SubscriptionList 对象 这里就是将  CompositeSubscription 添加到  SubscriptionList 中
            child.add(s);

            //capture the HystrixRequestContext upfront so that we can use it in the timeout thread later
            // 获取当前请求的上下文对象
            final HystrixRequestContext hystrixRequestContext = HystrixRequestContext.getContextForCurrentThread();

            // 监听器对象  该监听器对象的作用就是按照时间间隔 来 判断检查是否 TimedOutStatus 被修改成了 TIMED_OUT  当检测到这种情况时 触发下游的 onError 方法
            TimerListener listener = new TimerListener() {

                /**
                 * 监听器 一共包含2个方法一个是 每次 到时间时 触发的函数 一个是 返回 tick 的时间间隔
                 */
                @Override
                public void tick() {
                    // if we can go from NOT_EXECUTED to TIMED_OUT then we do the timeout codepath
                    // otherwise it means we lost a race and the run() execution completed or did not start
                    // 必须确保 从 Not_executed 从timeout 修改成功 默认情况下 初始情况 就是 NOT_EXECUTED
                    if (originalCommand.isCommandTimedOut.compareAndSet(TimedOutStatus.NOT_EXECUTED, TimedOutStatus.TIMED_OUT)) {
                        // report timeout failure
                        // 通知 出现了 超时事件
                        originalCommand.eventNotifier.markEvent(HystrixEventType.TIMEOUT, originalCommand.commandKey);

                        // shut down the original request
                        // 通过 该方式 能够 中断 数据源  调用unsubscribe 会遍历 内部的 SubscriptionList 依次进行 取消订阅
                        s.unsubscribe();

                        // 创建一个 会抛出异常的 runnable
                        final HystrixContextRunnable timeoutRunnable = new HystrixContextRunnable(originalCommand.concurrencyStrategy, hystrixRequestContext, new Runnable() {

                            @Override
                            public void run() {
                                child.onError(new HystrixTimeoutException());
                            }
                        });


                        // 触发异常
                        timeoutRunnable.run();
                        //if it did not start, then we need to mark a command start for concurrency metrics, and then issue the timeout
                    }
                }

                @Override
                public int getIntervalTimeInMilliseconds() {
                    return originalCommand.properties.executionTimeoutInMilliseconds().get();
                }
            };

            // 为 timer对象 增加listener  该对象 会在首次添加listener 时 启用定时器
            final Reference<TimerListener> tl = HystrixTimer.getInstance().addTimerListener(listener);

            // set externally so execute/queue can see this
            originalCommand.timeoutTimer.set(tl);

            /**
             * If this subscriber receives values it means the parent succeeded/completed
             * 该对象 是 代理后的对象
             */
            Subscriber<R> parent = new Subscriber<R>() {

                @Override
                public void onCompleted() {
                    // 判断当前状态是否是 执行完成 否则是 TimeOut
                    if (isNotTimedOut()) {
                        // stop timer and pass notification through
                        // 停止定时器
                        tl.clear();
                        child.onCompleted();
                    }
                }

                @Override
                public void onError(Throwable e) {
                    // 非超时异常才允许往下传
                    if (isNotTimedOut()) {
                        // stop timer and pass notification through
                        tl.clear();
                        child.onError(e);
                    }
                }

                @Override
                public void onNext(R v) {
                    // 非超时才传递
                    if (isNotTimedOut()) {
                        child.onNext(v);
                    }
                }

                /**
                 * 判断是否执行完成
                 * @return
                 */
                private boolean isNotTimedOut() {
                    // if already marked COMPLETED (by onNext) or succeeds in setting to COMPLETED
                    return originalCommand.isCommandTimedOut.get() == TimedOutStatus.COMPLETED ||
                            originalCommand.isCommandTimedOut.compareAndSet(TimedOutStatus.NOT_EXECUTED, TimedOutStatus.COMPLETED);
                }

            };

            // if s is unsubscribed we want to unsubscribe the parent
            // 添加到父对象中方便 之后取消订阅
            s.add(parent);

            return parent;
        }

    }

    /**
     * 根据需要设置 请求上下文对象
     * @param currentRequestContext
     */
    private static void setRequestContextIfNeeded(final HystrixRequestContext currentRequestContext) {
        // 判断本线程的 请求上下文对象是否已设置
        if (!HystrixRequestContext.isCurrentThreadInitialized()) {
            // even if the user Observable doesn't have context we want it set for chained operators
            // 没有的情况下 将command 维护的 上下文对象 设置到执行任务的线程中
            HystrixRequestContext.setContextOnCurrentThread(currentRequestContext);
        }
    }

    /**
     * Get the TryableSemaphore this HystrixCommand should use if a fallback occurs.
     *
     * @return TryableSemaphore
     * 获取回退的信号量
     */
    protected TryableSemaphore getFallbackSemaphore() {
        if (fallbackSemaphoreOverride == null) {
            // 获取针对 回退的信号量
            TryableSemaphore _s = fallbackSemaphorePerCircuit.get(commandKey.name());
            if (_s == null) {
                // we didn't find one cache so setup
                fallbackSemaphorePerCircuit.putIfAbsent(commandKey.name(), new TryableSemaphoreActual(properties.fallbackIsolationSemaphoreMaxConcurrentRequests()));
                // assign whatever got set (this or another thread)
                return fallbackSemaphorePerCircuit.get(commandKey.name());
            } else {
                return _s;
            }
        } else {
            return fallbackSemaphoreOverride;
        }
    }

    /**
     * Get the TryableSemaphore this HystrixCommand should use for execution if not running in a separate thread.
     *
     * @return TryableSemaphore
     * 获取 用于执行任务的信号量对象
     */
    protected TryableSemaphore getExecutionSemaphore() {
        // 首先确保 隔离策略使用的是 信号量
        if (properties.executionIsolationStrategy().get() == ExecutionIsolationStrategy.SEMAPHORE) {
            // 如果override 信号量为 null 该对象应该就是执行 过程中  使用的信号量
            if (executionSemaphoreOverride == null) {
                // 根据每个 command 获取专用的 semaphore 对象
                TryableSemaphore _s = executionSemaphorePerCircuit.get(commandKey.name());
                if (_s == null) {
                    // we didn't find one cache so setup
                    // 创建一个新的 信号量对象 并保存到 全局唯一容器中   executionIsolationSemaphoreMaxConcurrentRequests 代表信号量的门票数
                    executionSemaphorePerCircuit.putIfAbsent(commandKey.name(), new TryableSemaphoreActual(properties.executionIsolationSemaphoreMaxConcurrentRequests()));
                    // assign whatever got set (this or another thread)
                    return executionSemaphorePerCircuit.get(commandKey.name());
                } else {
                    return _s;
                }
            } else {
                return executionSemaphoreOverride;
            }
        } else {
            // return NoOp implementation since we're not using SEMAPHORE isolation
            return TryableSemaphoreNoOp.DEFAULT;
        }
    }

    /**
     * Each concrete implementation of AbstractCommand should return the name of the fallback method as a String
     * This will be used to determine if the fallback "exists" for firing the onFallbackStart/onFallbackError hooks
     * @deprecated This functionality is replaced by {@link #isFallbackUserDefined}, which is less implementation-aware
     * @return method name of fallback
     */
    @Deprecated
    protected abstract String getFallbackMethodName();

    protected abstract boolean isFallbackUserDefined();

    /**
     * @return {@link HystrixCommandGroupKey} used to group together multiple {@link AbstractCommand} objects.
     *         <p>
     *         The {@link HystrixCommandGroupKey} is used to represent a common relationship between commands. For example, a library or team name, the system all related commands interace with,
     *         common business purpose etc.
     */
    public HystrixCommandGroupKey getCommandGroup() {
        return commandGroup;
    }

    /**
     * @return {@link HystrixCommandKey} identifying this command instance for statistics, circuit-breaker, properties, etc.
     */
    public HystrixCommandKey getCommandKey() {
        return commandKey;
    }

    /**
     * @return {@link HystrixThreadPoolKey} identifying which thread-pool this command uses (when configured to run on separate threads via
     *         {@link HystrixCommandProperties#executionIsolationStrategy()}).
     */
    public HystrixThreadPoolKey getThreadPoolKey() {
        return threadPoolKey;
    }

    /* package */HystrixCircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }

    /**
     * The {@link HystrixCommandMetrics} associated with this {@link AbstractCommand} instance.
     *
     * @return HystrixCommandMetrics
     */
    public HystrixCommandMetrics getMetrics() {
        return metrics;
    }

    /**
     * The {@link HystrixCommandProperties} associated with this {@link AbstractCommand} instance.
     *
     * @return HystrixCommandProperties
     */
    public HystrixCommandProperties getProperties() {
        return properties;
    }

    /* ******************************************************************************** */
    /* ******************************************************************************** */
    /* Operators that implement hook application */
    /* ******************************************************************************** */
    /* ******************************************************************************** */

    /**
     * 执行应用时的 hook对象
     */
    private class ExecutionHookApplication implements Operator<R, R> {
        /**
         * 内部维护的 command 对象
         */
        private final HystrixInvokable<R> cmd;

        ExecutionHookApplication(HystrixInvokable<R> cmd) {
            this.cmd = cmd;
        }

        /**
         * 配合 lift 函数 对传入参数 进行代理
         * @param subscriber
         * @return
         */
        @Override
        public Subscriber<? super R> call(final Subscriber<? super R> subscriber) {
            return new Subscriber<R>(subscriber) {
                @Override
                public void onCompleted() {
                    try {
                        // 完成前 执行对应钩子 失败 打印日志并忽视异常
                        executionHook.onExecutionSuccess(cmd);
                    } catch (Throwable hookEx) {
                        logger.warn("Error calling HystrixCommandExecutionHook.onExecutionSuccess", hookEx);
                    }
                    subscriber.onCompleted();
                }

                /**
                 * 将异常包装后 触发钩子 并执行  onError
                 * @param e
                 */
                @Override
                public void onError(Throwable e) {
                    Exception wrappedEx = wrapWithOnExecutionErrorHook(e);
                    subscriber.onError(wrappedEx);
                }

                @Override
                public void onNext(R r) {
                    R wrappedValue = wrapWithOnExecutionEmitHook(r);
                    subscriber.onNext(wrappedValue);
                }
            };
        }
    }

    /**
     * 作用于 rxjava 的 lift 函数   实现代理功能
     * 该operator 对象就是在原对象调用任何方法前 执行 hook 指定的方法
     */
    private class FallbackHookApplication implements Operator<R, R> {

        /**
         * 目标原对象 这里特指 用户执行的command 对象
         */
        private final HystrixInvokable<R> cmd;

        FallbackHookApplication(HystrixInvokable<R> cmd) {
            this.cmd = cmd;
        }

        @Override
        public Subscriber<? super R> call(final Subscriber<? super R> subscriber) {
            // 装饰原有的订阅者对象
            return new Subscriber<R>(subscriber) {
                @Override
                public void onCompleted() {
                    try {
                        // 触发钩子相关方法
                        executionHook.onFallbackSuccess(cmd);
                    } catch (Throwable hookEx) {
                        logger.warn("Error calling HystrixCommandExecutionHook.onFallbackSuccess", hookEx);
                    }
                    subscriber.onCompleted();
                }

                @Override
                public void onError(Throwable e) {
                    // 执行方法前调用 hook
                    Exception wrappedEx = wrapWithOnFallbackErrorHook(e);
                    subscriber.onError(wrappedEx);
                }

                @Override
                public void onNext(R r) {
                    // 执行方法前调用 hook
                    R wrappedValue = wrapWithOnFallbackEmitHook(r);
                    subscriber.onNext(wrappedValue);
                }
            };
        }
    }

    @Deprecated //separated out to make it cleanly removable
    private class DeprecatedOnRunHookApplication implements Operator<R, R> {

        private final HystrixInvokable<R> cmd;

        DeprecatedOnRunHookApplication(HystrixInvokable<R> cmd) {
            this.cmd = cmd;
        }

        @Override
        public Subscriber<? super R> call(final Subscriber<? super R> subscriber) {
            return new Subscriber<R>(subscriber) {
                @Override
                public void onCompleted() {
                    subscriber.onCompleted();
                }

                @Override
                public void onError(Throwable t) {
                    Exception e = getExceptionFromThrowable(t);
                    try {
                        Exception wrappedEx = executionHook.onRunError(cmd, e);
                        subscriber.onError(wrappedEx);
                    } catch (Throwable hookEx) {
                        logger.warn("Error calling HystrixCommandExecutionHook.onRunError", hookEx);
                        subscriber.onError(e);
                    }
                }

                @Override
                public void onNext(R r) {
                    try {
                        R wrappedValue = executionHook.onRunSuccess(cmd, r);
                        subscriber.onNext(wrappedValue);
                    } catch (Throwable hookEx) {
                        logger.warn("Error calling HystrixCommandExecutionHook.onRunSuccess", hookEx);
                        subscriber.onNext(r);
                    }
                }
            };
        }
    }

    @Deprecated //separated out to make it cleanly removable
    private class DeprecatedOnFallbackHookApplication implements Operator<R, R> {

        private final HystrixInvokable<R> cmd;

        DeprecatedOnFallbackHookApplication(HystrixInvokable<R> cmd) {
            this.cmd = cmd;
        }

        @Override
        public Subscriber<? super R> call(final Subscriber<? super R> subscriber) {
            return new Subscriber<R>(subscriber) {
                @Override
                public void onCompleted() {
                    subscriber.onCompleted();
                }

                @Override
                public void onError(Throwable t) {
                    //no need to call a hook here.  FallbackHookApplication is already calling the proper and non-deprecated hook
                    subscriber.onError(t);
                }

                @Override
                public void onNext(R r) {
                    try {
                        R wrappedValue = executionHook.onFallbackSuccess(cmd, r);
                        subscriber.onNext(wrappedValue);
                    } catch (Throwable hookEx) {
                        logger.warn("Error calling HystrixCommandExecutionHook.onFallbackSuccess", hookEx);
                        subscriber.onNext(r);
                    }
                }
            };
        }
    }

    /**
     * 根据需要进行包装 并执行钩子
     * @param t
     * @return
     */
    private Exception wrapWithOnExecutionErrorHook(Throwable t) {
        Exception e = getExceptionFromThrowable(t);
        try {
            return executionHook.onExecutionError(this, e);
        } catch (Throwable hookEx) {
            logger.warn("Error calling HystrixCommandExecutionHook.onExecutionError", hookEx);
            return e;
        }
    }

    /**
     * 触发对应的钩子
     * @param t
     * @return
     */
    private Exception wrapWithOnFallbackErrorHook(Throwable t) {
        // 如果是Error 包装成 Exception
        Exception e = getExceptionFromThrowable(t);
        try {
            // 代表用户定义了 钩子吧
            if (isFallbackUserDefined()) {
                return executionHook.onFallbackError(this, e);
            } else {
                return e;
            }
        } catch (Throwable hookEx) {
            logger.warn("Error calling HystrixCommandExecutionHook.onFallbackError", hookEx);
            return e;
        }
    }

    /**
     * 将原始异常包装后返回
     * @param failureType
     * @param t
     * @return
     */
    private Exception wrapWithOnErrorHook(FailureType failureType, Throwable t) {
        Exception e = getExceptionFromThrowable(t);
        try {
            // 通知钩子触发了 异常事件
            return executionHook.onError(this, failureType, e);
        } catch (Throwable hookEx) {
            logger.warn("Error calling HystrixCommandExecutionHook.onError", hookEx);
            return e;
        }
    }

    private R wrapWithOnExecutionEmitHook(R r) {
        try {
            return executionHook.onExecutionEmit(this, r);
        } catch (Throwable hookEx) {
            logger.warn("Error calling HystrixCommandExecutionHook.onExecutionEmit", hookEx);
            return r;
        }
    }

    /**
     * 调用钩子相关方法
     * @param r
     * @return
     */
    private R wrapWithOnFallbackEmitHook(R r) {
        try {
            return executionHook.onFallbackEmit(this, r);
        } catch (Throwable hookEx) {
            logger.warn("Error calling HystrixCommandExecutionHook.onFallbackEmit", hookEx);
            return r;
        }
    }

    private R wrapWithOnEmitHook(R r) {
        try {
            return executionHook.onEmit(this, r);
        } catch (Throwable hookEx) {
            logger.warn("Error calling HystrixCommandExecutionHook.onEmit", hookEx);
            return r;
        }
    }

    /**
     * Take an Exception and determine whether to throw it, its cause or a new HystrixRuntimeException.
     * <p>
     * This will only throw an HystrixRuntimeException, HystrixBadRequestException, IllegalStateException
     * or any exception that implements ExceptionNotWrappedByHystrix.
     *
     * @param e initial exception
     * @return HystrixRuntimeException, HystrixBadRequestException or IllegalStateException
     * 将 被包装的异常对象从 包装中剥离
     */
    protected Throwable decomposeException(Exception e) {
        if (e instanceof IllegalStateException) {
            return (IllegalStateException) e;
        }
        // 如果是 hystrix 包装的异常
        if (e instanceof HystrixBadRequestException) {
            // 如果 是 NotBeWrapper 异常 解除 Wrapper
            if (shouldNotBeWrapped(e.getCause())) {
                return e.getCause();
            }
            return (HystrixBadRequestException) e;
        }
        // 如果内部包含的对象 是 被包装对象 解除包装后返回
        if (e.getCause() instanceof HystrixBadRequestException) {
            if (shouldNotBeWrapped(e.getCause().getCause())) {
                return e.getCause().getCause();
            }
            return (HystrixBadRequestException) e.getCause();
        }
        if (e instanceof HystrixRuntimeException) {
            return (HystrixRuntimeException) e;
        }
        // if we have an exception we know about we'll throw it directly without the wrapper exception
        if (e.getCause() instanceof HystrixRuntimeException) {
            return (HystrixRuntimeException) e.getCause();
        }
        if (shouldNotBeWrapped(e)) {
            return e;
        }
        if (shouldNotBeWrapped(e.getCause())) {
            return e.getCause();
        }
        // we don't know what kind of exception this is so create a generic message and throw a new HystrixRuntimeException
        String message = getLogMessagePrefix() + " failed while executing.";
        logger.debug(message, e); // debug only since we're throwing the exception and someone higher will do something with it
        return new HystrixRuntimeException(FailureType.COMMAND_EXCEPTION, this.getClass(), message, e, null);

    }

    /* ******************************************************************************** */
    /* ******************************************************************************** */
    /* TryableSemaphore */
    /* ******************************************************************************** */
    /* ******************************************************************************** */

    /**
     * Semaphore that only supports tryAcquire and never blocks and that supports a dynamic permit count.
     * <p>
     * Using AtomicInteger increment/decrement instead of java.util.concurrent.Semaphore since we don't need blocking and need a custom implementation to get the dynamic permit count and since
     * AtomicInteger achieves the same behavior and performance without the more complex implementation of the actual Semaphore class using AbstractQueueSynchronizer.
     * 可重试的 信号量对象   只是一个模拟的  Semaphore  而不是拓展juc 的信号量
     */
    /* package */static class TryableSemaphoreActual implements TryableSemaphore {
        /**
         * 门票数量
         */
        protected final HystrixProperty<Integer> numberOfPermits;
        /**
         * 计数
         */
        private final AtomicInteger count = new AtomicInteger(0);

        public TryableSemaphoreActual(HystrixProperty<Integer> numberOfPermits) {
            this.numberOfPermits = numberOfPermits;
        }

        /**
         * count 值等同于当前的门票数量
         * @return
         */
        @Override
        public boolean tryAcquire() {
            int currentCount = count.incrementAndGet();
            if (currentCount > numberOfPermits.get()) {
                count.decrementAndGet();
                return false;
            } else {
                return true;
            }
        }

        @Override
        public void release() {
            count.decrementAndGet();
        }

        @Override
        public int getNumberOfPermitsUsed() {
            return count.get();
        }

    }

    /**
     * 一个空的 重试信号量对象
     */
    /* package */static class TryableSemaphoreNoOp implements TryableSemaphore {

        public static final TryableSemaphore DEFAULT = new TryableSemaphoreNoOp();

        /**
         * 默认总是能
         * @return
         */
        @Override
        public boolean tryAcquire() {
            return true;
        }

        @Override
        public void release() {

        }

        /**
         * 默认返回使用的 门票为0
         * @return
         */
        @Override
        public int getNumberOfPermitsUsed() {
            return 0;
        }

    }

    /**
     * 可重试的信号量
     */
    /* package */static interface TryableSemaphore {

        /**
         * Use like this:
         * <p>
         *
         * <pre>
         * if (s.tryAcquire()) {
         * try {
         * // do work that is protected by 's'
         * } finally {
         * s.release();
         * }
         * }
         * </pre>
         * 尝试获取信号量的门票
         *
         * @return boolean
         */
        public abstract boolean tryAcquire();

        /**
         * ONLY call release if tryAcquire returned true.
         * <p>
         *
         * <pre>
         * if (s.tryAcquire()) {
         * try {
         * // do work that is protected by 's'
         * } finally {
         * s.release();
         * }
         * }
         * </pre>
         * 释放信号量
         */
        public abstract void release();

        /**
         * 获取门票数量
         * @return
         */
        public abstract int getNumberOfPermitsUsed();

    }

    /* ******************************************************************************** */
    /* ******************************************************************************** */
    /* RequestCache */
    /* ******************************************************************************** */
    /* ******************************************************************************** */

    /**
     * Key to be used for request caching.
     * <p>
     * By default this returns null which means "do not cache".
     * <p>
     * To enable caching override this method and return a string key uniquely representing the state of a command instance.
     * <p>
     * If multiple command instances in the same request scope match keys then only the first will be executed and all others returned from cache.
     *
     * @return cacheKey
     * 获取缓存键
     */
    protected String getCacheKey() {
        return null;
    }

    /**
     * 获取公共缓存键
     * @return
     */
    public String getPublicCacheKey() {
        return getCacheKey();
    }

    protected boolean isRequestCachingEnabled() {
        // 默认情况下  允许使用缓存  而 缓存键为null
        return properties.requestCacheEnabled().get() && getCacheKey() != null;
    }

    protected String getLogMessagePrefix() {
        return getCommandKey().name();
    }

    /**
     * Whether the 'circuit-breaker' is open meaning that <code>execute()</code> will immediately return
     * the <code>getFallback()</code> response and not attempt a HystrixCommand execution.
     *
     * 4 columns are ForcedOpen | ForcedClosed | CircuitBreaker open due to health ||| Expected Result
     *
     * T | T | T ||| OPEN (true)
     * T | T | F ||| OPEN (true)
     * T | F | T ||| OPEN (true)
     * T | F | F ||| OPEN (true)
     * F | T | T ||| CLOSED (false)
     * F | T | F ||| CLOSED (false)
     * F | F | T ||| OPEN (true)
     * F | F | F ||| CLOSED (false)
     *
     * @return boolean
     */
    public boolean isCircuitBreakerOpen() {
        return properties.circuitBreakerForceOpen().get() || (!properties.circuitBreakerForceClosed().get() && circuitBreaker.isOpen());
    }

    /**
     * If this command has completed execution either successfully, via fallback or failure.
     *
     * @return boolean
     */
    public boolean isExecutionComplete() {
        return commandState.get() == CommandState.TERMINAL;
    }

    /**
     * Whether the execution occurred in a separate thread.
     * <p>
     * This should be called only once execute()/queue()/fireOrForget() are called otherwise it will always return false.
     * <p>
     * This specifies if a thread execution actually occurred, not just if it is configured to be executed in a thread.
     *
     * @return boolean
     */
    public boolean isExecutedInThread() {
        return getCommandResult().isExecutedInThread();
    }

    /**
     * Whether the response was returned successfully either by executing <code>run()</code> or from cache.
     *
     * @return boolean
     */
    public boolean isSuccessfulExecution() {
        return getCommandResult().getEventCounts().contains(HystrixEventType.SUCCESS);
    }

    /**
     * Whether the <code>run()</code> resulted in a failure (exception).
     *
     * @return boolean
     */
    public boolean isFailedExecution() {
        return getCommandResult().getEventCounts().contains(HystrixEventType.FAILURE);
    }

    /**
     * Get the Throwable/Exception thrown that caused the failure.
     * <p>
     * If <code>isFailedExecution() == true</code> then this would represent the Exception thrown by the <code>run()</code> method.
     * <p>
     * If <code>isFailedExecution() == false</code> then this would return null.
     *
     * @return Throwable or null
     */
    public Throwable getFailedExecutionException() {
        return executionResult.getException();
    }

    /**
     * Get the Throwable/Exception emitted by this command instance prior to checking the fallback.
     * This exception instance may have been generated via a number of mechanisms:
     * 1) failed execution (in this case, same result as {@link #getFailedExecutionException()}.
     * 2) timeout
     * 3) short-circuit
     * 4) rejection
     * 5) bad request
     *
     * If the command execution was successful, then this exception instance is null (there was no exception)
     *
     * Note that the caller of the command may not receive this exception, as fallbacks may be served as a response to
     * the exception.
     *
     * @return Throwable or null
     */
    public Throwable getExecutionException() {
        return executionResult.getExecutionException();
    }

    /**
     * Whether the response received from was the result of some type of failure
     * and <code>getFallback()</code> being called.
     *
     * @return boolean
     */
    public boolean isResponseFromFallback() {
        return getCommandResult().getEventCounts().contains(HystrixEventType.FALLBACK_SUCCESS);
    }

    /**
     * Whether the response received was the result of a timeout
     * and <code>getFallback()</code> being called.
     *
     * @return boolean
     */
    public boolean isResponseTimedOut() {
        return getCommandResult().getEventCounts().contains(HystrixEventType.TIMEOUT);
    }

    /**
     * Whether the response received was a fallback as result of being
     * short-circuited (meaning <code>isCircuitBreakerOpen() == true</code>) and <code>getFallback()</code> being called.
     *
     * @return boolean
     */
    public boolean isResponseShortCircuited() {
        return getCommandResult().getEventCounts().contains(HystrixEventType.SHORT_CIRCUITED);
    }

    /**
     * Whether the response is from cache and <code>run()</code> was not invoked.
     *
     * @return boolean
     */
    public boolean isResponseFromCache() {
        return isResponseFromCache;
    }

    /**
     * Whether the response received was a fallback as result of being rejected via sempahore
     *
     * @return boolean
     */
    public boolean isResponseSemaphoreRejected() {
        return getCommandResult().isResponseSemaphoreRejected();
    }

    /**
     * Whether the response received was a fallback as result of being rejected via threadpool
     *
     * @return boolean
     */
    public boolean isResponseThreadPoolRejected() {
        return getCommandResult().isResponseThreadPoolRejected();
    }

    /**
     * Whether the response received was a fallback as result of being rejected (either via threadpool or semaphore)
     *
     * @return boolean
     */
    public boolean isResponseRejected() {
        return getCommandResult().isResponseRejected();
    }

    /**
     * List of HystrixCommandEventType enums representing events that occurred during execution.
     * <p>
     * Examples of events are SUCCESS, FAILURE, TIMEOUT, and SHORT_CIRCUITED
     *
     * @return {@code List<HystrixEventType>}
     */
    public List<HystrixEventType> getExecutionEvents() {
        return getCommandResult().getOrderedList();
    }

    /**
     * 获取 command 结果对象
     * @return
     */
    private ExecutionResult getCommandResult() {
        ExecutionResult resultToReturn;
        if (executionResultAtTimeOfCancellation == null) {
            resultToReturn = executionResult;
        } else {
            resultToReturn = executionResultAtTimeOfCancellation;
        }

        if (isResponseFromCache) {
            resultToReturn = resultToReturn.addEvent(HystrixEventType.RESPONSE_FROM_CACHE);
        }

        return resultToReturn;
    }

    /**
     * Number of emissions of the execution of a command.  Only interesting in the streaming case.
     * @return number of <code>OnNext</code> emissions by a streaming command
     */
    @Override
    public int getNumberEmissions() {
        return getCommandResult().getEventCounts().getCount(HystrixEventType.EMIT);
    }

    /**
     * Number of emissions of the execution of a fallback.  Only interesting in the streaming case.
     * @return number of <code>OnNext</code> emissions by a streaming fallback
     */
    @Override
    public int getNumberFallbackEmissions() {
        return getCommandResult().getEventCounts().getCount(HystrixEventType.FALLBACK_EMIT);
    }

    @Override
    public int getNumberCollapsed() {
        return getCommandResult().getEventCounts().getCount(HystrixEventType.COLLAPSED);
    }

    @Override
    public HystrixCollapserKey getOriginatingCollapserKey() {
        return executionResult.getCollapserKey();
    }

    /**
     * The execution time of this command instance in milliseconds, or -1 if not executed.
     *
     * @return int
     */
    public int getExecutionTimeInMilliseconds() {
        return getCommandResult().getExecutionLatency();
    }

    /**
     * Time in Nanos when this command instance's run method was called, or -1 if not executed 
     * for e.g., command threw an exception
     *
     * @return long
     */
    public long getCommandRunStartTimeInNanos() {
        return executionResult.getCommandRunStartTimeInNanos();
    }

    @Override
    public ExecutionResult.EventCounts getEventCounts() {
        return getCommandResult().getEventCounts();
    }

    /**
     * 将 Error 包装成 Exception 并返回
     * @param t
     * @return
     */
    protected Exception getExceptionFromThrowable(Throwable t) {
        Exception e;
        if (t instanceof Exception) {
            e = (Exception) t;
        } else {
            // Hystrix 1.x uses Exception, not Throwable so to prevent a breaking change Throwable will be wrapped in Exception
            e = new Exception("Throwable caught while executing.", t);
        }
        return e;
    }

    /**
     * 在 Command 对象中 没有直接使用 hook 对象而是 使用 hook的包装对象
     */
    private static class ExecutionHookDeprecationWrapper extends HystrixCommandExecutionHook {

        /**
         * hook 内部有针对 Command 执行流程中对应触发的 切入点
         */
        private final HystrixCommandExecutionHook actual;

        ExecutionHookDeprecationWrapper(HystrixCommandExecutionHook actual) {
            this.actual = actual;
        }

        @Override
        public <T> T onEmit(HystrixInvokable<T> commandInstance, T value) {
            return actual.onEmit(commandInstance, value);
        }

        @Override
        public <T> void onSuccess(HystrixInvokable<T> commandInstance) {
            actual.onSuccess(commandInstance);
        }

        @Override
        public <T> void onExecutionStart(HystrixInvokable<T> commandInstance) {
            actual.onExecutionStart(commandInstance);
        }

        @Override
        public <T> T onExecutionEmit(HystrixInvokable<T> commandInstance, T value) {
            return actual.onExecutionEmit(commandInstance, value);
        }

        @Override
        public <T> Exception onExecutionError(HystrixInvokable<T> commandInstance, Exception e) {
            return actual.onExecutionError(commandInstance, e);
        }

        @Override
        public <T> void onExecutionSuccess(HystrixInvokable<T> commandInstance) {
            actual.onExecutionSuccess(commandInstance);
        }

        @Override
        public <T> T onFallbackEmit(HystrixInvokable<T> commandInstance, T value) {
            return actual.onFallbackEmit(commandInstance, value);
        }

        @Override
        public <T> void onFallbackSuccess(HystrixInvokable<T> commandInstance) {
            actual.onFallbackSuccess(commandInstance);
        }

        @Override
        @Deprecated
        public <T> void onRunStart(HystrixCommand<T> commandInstance) {
            actual.onRunStart(commandInstance);
        }

        @Override
        public <T> void onRunStart(HystrixInvokable<T> commandInstance) {
            HystrixCommand<T> c = getHystrixCommandFromAbstractIfApplicable(commandInstance);
            if (c != null) {
                onRunStart(c);
            }
            actual.onRunStart(commandInstance);
        }

        @Override
        @Deprecated
        public <T> T onRunSuccess(HystrixCommand<T> commandInstance, T response) {
            return actual.onRunSuccess(commandInstance, response);
        }

        @Override
        @Deprecated
        public <T> T onRunSuccess(HystrixInvokable<T> commandInstance, T response) {
            HystrixCommand<T> c = getHystrixCommandFromAbstractIfApplicable(commandInstance);
            if (c != null) {
                response = onRunSuccess(c, response);
            }
            return actual.onRunSuccess(commandInstance, response);
        }

        @Override
        @Deprecated
        public <T> Exception onRunError(HystrixCommand<T> commandInstance, Exception e) {
            return actual.onRunError(commandInstance, e);
        }

        @Override
        @Deprecated
        public <T> Exception onRunError(HystrixInvokable<T> commandInstance, Exception e) {
            HystrixCommand<T> c = getHystrixCommandFromAbstractIfApplicable(commandInstance);
            if (c != null) {
                e = onRunError(c, e);
            }
            return actual.onRunError(commandInstance, e);
        }

        @Override
        @Deprecated
        public <T> void onFallbackStart(HystrixCommand<T> commandInstance) {
            actual.onFallbackStart(commandInstance);
        }

        @Override
        public <T> void onFallbackStart(HystrixInvokable<T> commandInstance) {
            HystrixCommand<T> c = getHystrixCommandFromAbstractIfApplicable(commandInstance);
            if (c != null) {
                onFallbackStart(c);
            }
            actual.onFallbackStart(commandInstance);
        }

        @Override
        @Deprecated
        public <T> T onFallbackSuccess(HystrixCommand<T> commandInstance, T fallbackResponse) {
            return actual.onFallbackSuccess(commandInstance, fallbackResponse);
        }

        @Override
        @Deprecated
        public <T> T onFallbackSuccess(HystrixInvokable<T> commandInstance, T fallbackResponse) {
            HystrixCommand<T> c = getHystrixCommandFromAbstractIfApplicable(commandInstance);
            if (c != null) {
                fallbackResponse = onFallbackSuccess(c, fallbackResponse);
            }
            return actual.onFallbackSuccess(commandInstance, fallbackResponse);
        }

        @Override
        @Deprecated
        public <T> Exception onFallbackError(HystrixCommand<T> commandInstance, Exception e) {
            return actual.onFallbackError(commandInstance, e);
        }

        @Override
        public <T> Exception onFallbackError(HystrixInvokable<T> commandInstance, Exception e) {
            HystrixCommand<T> c = getHystrixCommandFromAbstractIfApplicable(commandInstance);
            if (c != null) {
                e = onFallbackError(c, e);
            }
            return actual.onFallbackError(commandInstance, e);
        }

        @Override
        @Deprecated
        public <T> void onStart(HystrixCommand<T> commandInstance) {
            actual.onStart(commandInstance);
        }

        @Override
        public <T> void onStart(HystrixInvokable<T> commandInstance) {
            HystrixCommand<T> c = getHystrixCommandFromAbstractIfApplicable(commandInstance);
            if (c != null) {
                onStart(c);
            }
            actual.onStart(commandInstance);
        }

        @Override
        @Deprecated
        public <T> T onComplete(HystrixCommand<T> commandInstance, T response) {
            return actual.onComplete(commandInstance, response);
        }

        @Override
        @Deprecated
        public <T> T onComplete(HystrixInvokable<T> commandInstance, T response) {
            HystrixCommand<T> c = getHystrixCommandFromAbstractIfApplicable(commandInstance);
            if (c != null) {
                response = onComplete(c, response);
            }
            return actual.onComplete(commandInstance, response);
        }

        @Override
        @Deprecated
        public <T> Exception onError(HystrixCommand<T> commandInstance, FailureType failureType, Exception e) {
            return actual.onError(commandInstance, failureType, e);
        }

        @Override
        public <T> Exception onError(HystrixInvokable<T> commandInstance, FailureType failureType, Exception e) {
            HystrixCommand<T> c = getHystrixCommandFromAbstractIfApplicable(commandInstance);
            if (c != null) {
                e = onError(c, failureType, e);
            }
            return actual.onError(commandInstance, failureType, e);
        }

        @Override
        @Deprecated
        public <T> void onThreadStart(HystrixCommand<T> commandInstance) {
            actual.onThreadStart(commandInstance);
        }

        @Override
        public <T> void onThreadStart(HystrixInvokable<T> commandInstance) {
            HystrixCommand<T> c = getHystrixCommandFromAbstractIfApplicable(commandInstance);
            if (c != null) {
                onThreadStart(c);
            }
            actual.onThreadStart(commandInstance);
        }

        @Override
        @Deprecated
        public <T> void onThreadComplete(HystrixCommand<T> commandInstance) {
            actual.onThreadComplete(commandInstance);
        }

        @Override
        public <T> void onThreadComplete(HystrixInvokable<T> commandInstance) {
            HystrixCommand<T> c = getHystrixCommandFromAbstractIfApplicable(commandInstance);
            if (c != null) {
                onThreadComplete(c);
            }
            actual.onThreadComplete(commandInstance);
        }

        @Override
        public <T> void onCacheHit(HystrixInvokable<T> commandInstance) {
            actual.onCacheHit(commandInstance);
        }

        @Override
        public <T> void onUnsubscribe(HystrixInvokable<T> commandInstance) {
            actual.onUnsubscribe(commandInstance);
        }

        /**
         * 在调用每个对应的 钩子方法前 执行  确保 invokable 是 HystrixCommand 对象
         */
        @SuppressWarnings({"unchecked", "rawtypes"})
        private <T> HystrixCommand<T> getHystrixCommandFromAbstractIfApplicable(HystrixInvokable<T> commandInstance) {
            if (commandInstance instanceof HystrixCommand) {
                return (HystrixCommand) commandInstance;
            } else {
                return null;
            }
        }
    }
}
