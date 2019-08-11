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

import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default implementation of {@link HystrixRequestVariable}. Similar to {@link ThreadLocal} but scoped at the user request level. Context is managed via {@link HystrixRequestContext}.
 * <p>
 * All statements below assume that child threads are spawned and initialized with the use of {@link HystrixContextCallable} or {@link HystrixContextRunnable} which capture state from a parent thread
 * and propagate to the child thread.
 * <p>
 * Characteristics that differ from ThreadLocal:
 * <ul>
 * <li>HystrixRequestVariable context must be initialized at the beginning of every request by {@link HystrixRequestContext#initializeContext}</li>
 * <li>HystrixRequestVariables attached to a thread will be cleared at the end of every user request by {@link HystrixRequestContext#shutdown} which execute {@link #remove} for each
 * HystrixRequestVariable</li>
 * <li>HystrixRequestVariables have a {@link #shutdown} lifecycle method that gets called at the end of every user request (invoked when {@link HystrixRequestContext#shutdown} is called) to allow for
 * resource cleanup.</li>
 * <li>HystrixRequestVariables are copied (by reference) to child threads via the {@link HystrixRequestContext#getContextForCurrentThread} and {@link HystrixRequestContext#setContextOnCurrentThread}
 * functionality.</li>
 * <li>HystrixRequestVariables created on a child thread are available on sibling and parent threads.</li>
 * <li>HystrixRequestVariables created on a child thread will be cleaned up by the parent thread via the {@link #shutdown} method.</li>
 * </ul>
 * 
 * <p>
 * Note on thread-safety: By design a HystrixRequestVariables is intended to be accessed by all threads in a user request, thus anything stored in a HystrixRequestVariables must be thread-safe and
 * plan on being accessed/mutated concurrently.
 * <p>
 * For example, a HashMap would likely not be a good choice for a RequestVariable value, but ConcurrentHashMap would.
 * 
 * @param <T>
 *            Type to be stored on the HystrixRequestVariable
 *            <p>
 *            Example 1: {@code HystrixRequestVariable<ConcurrentHashMap<String, DataObject>>} <p>
 *            Example 2: {@code HystrixRequestVariable<PojoThatIsThreadSafe>}
 * 
 * @ExcludeFromJavadoc
 * @ThreadSafe
 * 默认的请求变量  该类对于用户来说 就是一个 模子 需要子类去修改核心方法
 */
public class HystrixRequestVariableDefault<T> implements HystrixRequestVariable<T> {
    static final Logger logger = LoggerFactory.getLogger(HystrixRequestVariableDefault.class);

    /**
     * Creates a new HystrixRequestVariable that will exist across all threads
     * within a {@link HystrixRequestContext}
     */
    public HystrixRequestVariableDefault() {
    }

    /**
     * Get the current value for this variable for the current request context.
     * 
     * @return the value of the variable for the current request,
     *         or null if no value has been set and there is no initial value
     *         获取当前上下文中保存的值
     */
    @SuppressWarnings("unchecked")
    public T get() {
        // 获取当前线程的上下文对象 如果为空 抛出异常
        if (HystrixRequestContext.getContextForCurrentThread() == null) {
            throw new IllegalStateException(HystrixRequestContext.class.getSimpleName() + ".initializeContext() must be called at the beginning of each request before RequestVariable functionality can be used.");
        }
        // 获取当前上下文对象保存的容器
        ConcurrentHashMap<HystrixRequestVariableDefault<?>, LazyInitializer<?>> variableMap = HystrixRequestContext.getContextForCurrentThread().state;

        // short-circuit the synchronized path below if we already have the value in the ConcurrentHashMap
        // 从本线程绑定的上下文对象通过自身获取对应的 延迟初始化对象
        LazyInitializer<?> v = variableMap.get(this);
        if (v != null) {
            // 延迟加载对象
            return (T) v.get();
        }

        /*
         * Optimistically create a LazyInitializer to put into the ConcurrentHashMap.
         * 
         * The LazyInitializer will not invoke initialValue() unless the get() method is invoked
         * so we can optimistically instantiate LazyInitializer and then discard for garbage collection
         * if the putIfAbsent fails.
         * 
         * Whichever instance of LazyInitializer succeeds will then have get() invoked which will call
         * the initialValue() method once-and-only-once.
         * 如果没有获取对应的 value 就代表需要重新生成 lazy 初始化对象 并设置
         */
        LazyInitializer<T> l = new LazyInitializer<T>(this);
        LazyInitializer<?> existing = variableMap.putIfAbsent(this, l);
        if (existing == null) {
            /*
             * We won the thread-race so can use 'l' that we just created.
             * 主动触发 使得延迟初始化对象能够返回需要的值
             */
            return l.get();
        } else {
            /*
             * We lost the thread-race so let 'l' be garbage collected and instead return 'existing'
             * 代表被并发设置了 这里返回需要的值
             */
            return (T) existing.get();
        }
    }

    /**
     * Computes the initial value of the HystrixRequestVariable in a request.
     * <p>
     * This is called the first time the value of the HystrixRequestVariable is fetched in a request. Override this to provide an initial value for a HystrixRequestVariable on each request on which it
     * is used.
     * 
     * The default implementation returns null.
     * 
     * @return initial value of the HystrixRequestVariable to use for the instance being constructed
     * 这里进行初始化时 并不会生成对象  而 lazyInit 对象实际就是委托该方法
     */
    public T initialValue() {
        return null;
    }

    /**
     * Sets the value of the HystrixRequestVariable for the current request context.
     * <p>
     * Note, if a value already exists, the set will result in overwriting that value. It is up to the caller to ensure the existing value is cleaned up. The {@link #shutdown} method will not be
     * called
     * 
     * @param value
     *            the value to set
     *            将传入的 值 绑定到 上下文对应的容器对象中  注意这里的 lazyInit 是由 本对象和 value 组成的
     */
    public void set(T value) {
        HystrixRequestContext.getContextForCurrentThread().state.put(this, new LazyInitializer<T>(this, value));
    }

    /**
     * Removes the value of the HystrixRequestVariable from the current request.
     * <p>
     * This will invoke {@link #shutdown} if implemented.
     * <p>
     * If the value is subsequently fetched in the thread, the {@link #initialValue} method will be called again.
     * 从当前线程中 移除自身  应该就是从对应的上下文对象中 将自身关联的键值对从容器中移除
     */
    public void remove() {
        if (HystrixRequestContext.getContextForCurrentThread() != null) {
            remove(HystrixRequestContext.getContextForCurrentThread(), this);
        }
    }

    @SuppressWarnings("unchecked")
    /* package */static <T> void remove(HystrixRequestContext context, HystrixRequestVariableDefault<T> v) {
        // remove first so no other threads get it
        LazyInitializer<?> o = context.state.remove(v);
        if (o != null) {
            // this thread removed it so let's execute shutdown
            // 如果对象已经被初始化 就要 调用 shutdown 关闭它
            v.shutdown((T) o.get());
        }
    }

    /**
     * Provide life-cycle hook for a HystrixRequestVariable implementation to perform cleanup
     * before the HystrixRequestVariable is removed from the current thread.
     * <p>
     * This is executed at the end of each user request when {@link HystrixRequestContext#shutdown} is called or whenever {@link #remove} is invoked.
     * <p>
     * By default does nothing.
     * <p>
     * NOTE: Do not call <code>get()</code> from within this method or <code>initialValue()</code> will be invoked again. The current value is passed in as an argument.
     * 
     * @param value
     *            the value of the HystrixRequestVariable being removed
     *            默认情况下 shutdown是空实现
     */
    public void shutdown(T value) {
        // do nothing by default
    }

    /**
     * Holder for a value that can be derived from the {@link HystrixRequestVariableDefault#initialValue} method that needs
     * to be executed once-and-only-once.
     * <p>
     * This class can be instantiated and garbage collected without calling initialValue() as long as the get() method is not invoked and can thus be used with compareAndSet in
     * ConcurrentHashMap.putIfAbsent and allow "losers" in a thread-race to be discarded.
     * 
     * @param <T>
     *     延迟初始化对象
     */
    /* package */static final class LazyInitializer<T> {
        // @GuardedBy("synchronization on get() or construction")
        /**
         * 对应要生成/维护的值
         */
        private T value;

        /*
         * Boolean to ensure only-once initialValue() execution instead of using
         * a null check in case initialValue() returns null
         */
        // @GuardedBy("synchronization on get() or construction")
        /**
         * 默认情况下 还没有初始化
         */
        private boolean initialized = false;

        /**
         * 该对象内部 维护了 关联的 requestVariableDefault 对象
         */
        private final HystrixRequestVariableDefault<T> rv;

        /**
         * 这种初始化方式 就是还没有 生成value  且对应的 标识 还是false
         * @param rv
         */
        private LazyInitializer(HystrixRequestVariableDefault<T> rv) {
            this.rv = rv;
        }

        private LazyInitializer(HystrixRequestVariableDefault<T> rv, T value) {
            this.rv = rv;
            this.value = value;
            this.initialized = true;
        }

        /**
         * 延迟初始化  就是调用 variableDefault 的 initialValue
         * @return
         */
        public synchronized T get() {
            if (!initialized) {
                value = rv.initialValue();
                initialized = true;
            }
            return value;
        }
    }
}
