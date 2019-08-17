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

import java.io.Closeable;
import java.util.concurrent.ConcurrentHashMap;

import com.netflix.hystrix.HystrixCollapser;
import com.netflix.hystrix.HystrixCommand;
import com.netflix.hystrix.HystrixRequestCache;
import com.netflix.hystrix.HystrixRequestLog;

/**
 * Contains the state and manages the lifecycle of {@link HystrixRequestVariableDefault} objects that provide request scoped (rather than only thread scoped) variables so that multiple threads within
 * a
 * single request can share state:
 * <ul>
 * <li>request scoped caching as in {@link HystrixRequestCache} for de-duping {@link HystrixCommand} executions</li>
 * <li>request scoped log of all events as in {@link HystrixRequestLog}</li>
 * <li>automated batching of {@link HystrixCommand} executions within the scope of a request as in {@link HystrixCollapser}</li>
 * </ul>
 * <p>
 * If those features are not used then this does not need to be used. If those features are used then this must be initialized or a custom implementation of {@link HystrixRequestVariable} must be
 * returned from {@link HystrixConcurrencyStrategy#getRequestVariable}.
 * <p>
 * If {@link HystrixRequestVariableDefault} is used (directly or indirectly by above-mentioned features) and this context has not been initialized then an {@link IllegalStateException} will be thrown
 * with a
 * message such as: <blockquote> HystrixRequestContext.initializeContext() must be called at the beginning of each request before RequestVariable functionality can be used. </blockquote>
 * <p>
 * Example ServletFilter for initializing {@link HystrixRequestContext} at the beginning of an HTTP request and shutting down at the end:
 * 
 * <blockquote>
 * 
 * <pre>
 * public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
 *      HystrixRequestContext context = HystrixRequestContext.initializeContext();
 *      try {
 *           chain.doFilter(request, response);
 *      } finally {
 *           context.shutdown();
 *      }
 * }
 * </pre>
 * 
 * </blockquote>
 * <p>
 * You can find an implementation at <a target="_top" href="https://github.com/Netflix/Hystrix/tree/master/hystrix-contrib/hystrix-request-servlet">hystrix-contrib/hystrix-request-servlet</a> on GitHub.
 * <p>
 * <b>NOTE:</b> If <code>initializeContext()</code> is called then <code>shutdown()</code> must also be called or a memory leak will occur.
 * hystrix 请求上下文对象 实现 closeable 接口
 */
public class HystrixRequestContext implements Closeable {

    /*
     * ThreadLocal on each thread will hold the HystrixRequestVariableState.
     * 
     * Shutdown will clear the state inside HystrixRequestContext but not nullify the ThreadLocal on all
     * child threads as these threads will not be known by the parent when cleanupAfterRequest() is called.
     * 
     * However, the only thing held by those child threads until they are re-used and re-initialized is an empty
     * HystrixRequestContext object with the ConcurrentHashMap within it nulled out since once it is nullified
     * from the parent thread it is shared across all child threads.
     * 本地线程变量
     */
    private static ThreadLocal<HystrixRequestContext> requestVariables = new ThreadLocal<HystrixRequestContext>();

    /**
     * 判断本线程绑定的上下文对象是否初始化完成
     * @return
     */
    public static boolean isCurrentThreadInitialized() {
        HystrixRequestContext context = requestVariables.get();
        return context != null && context.state != null;
    }

    /**
     * 获取当前上下文对象
     * @return
     */
    public static HystrixRequestContext getContextForCurrentThread() {
        HystrixRequestContext context = requestVariables.get();
        // 代表存在上下文对象  注意这里的 state 必须是 非空才被承认 context 被首次初始化时  state 就会被创建 (就是一个 容器对象)当调用 shutdown() 时 state 会被置空
        if (context != null && context.state != null) {
            // context.state can be null when context is not null
            // if a thread is being re-used and held a context previously, the context was shut down
            // but the thread was not cleared
            return context;
        } else {
            return null;
        }
    }

    /**
     * 为绑定线程设置上下文
     * @param state
     */
    public static void setContextOnCurrentThread(HystrixRequestContext state) {
        requestVariables.set(state);
    }

    /**
     * Call this at the beginning of each request (from parent thread)
     * to initialize the underlying context so that {@link HystrixRequestVariableDefault} can be used on any children threads and be accessible from
     * the parent thread.
     * <p>
     * <b>NOTE: If this method is called then <code>shutdown()</code> must also be called or a memory leak will occur.</b>
     * <p>
     * See class header JavaDoc for example Servlet Filter implementation that initializes and shuts down the context.
     * 初始化上下文对象
     */
    public static HystrixRequestContext initializeContext() {
        HystrixRequestContext state = new HystrixRequestContext();
        requestVariables.set(state);
        return state;
    }

    /*
     * This ConcurrentHashMap should not be made publicly accessible. It is the state of RequestVariables for a given RequestContext.
     * 
     * Only HystrixRequestVariable has a reason to be accessing this field.
     * 本上下文维护的 state
     */
    /* package */ConcurrentHashMap<HystrixRequestVariableDefault<?>, HystrixRequestVariableDefault.LazyInitializer<?>> state = new ConcurrentHashMap<HystrixRequestVariableDefault<?>, HystrixRequestVariableDefault.LazyInitializer<?>>();

    // instantiation should occur via static factory methods.
    private HystrixRequestContext() {

    }

    /**
     * Shutdown {@link HystrixRequestVariableDefault} objects in this context.
     * <p>
     * <b>NOTE: This must be called if <code>initializeContext()</code> was called or a memory leak will occur.</b>
     * 终止本上下文对象
     */
    public void shutdown() {
        // 因为 state 不为 null 应该是代表当前上下文对象有效
        if (state != null) {
            // 获取所有的requestVariable
            for (HystrixRequestVariableDefault<?> v : state.keySet()) {
                // for each RequestVariable we call 'remove' which performs the shutdown logic
                try {
                    // 将对象移除
                    HystrixRequestVariableDefault.remove(this, v);
                } catch (Throwable t) {
                    HystrixRequestVariableDefault.logger.error("Error in shutdown, will continue with shutdown of other variables", t);
                }
            }
            // null out so it can be garbage collected even if the containing object is still
            // being held in ThreadLocals on threads that weren't cleaned up
            state = null;
        }
    }

    /**
     * Shutdown {@link HystrixRequestVariableDefault} objects in this context.
     * <p>
     * <b>NOTE: This must be called if <code>initializeContext()</code> was called or a memory leak will occur.</b>
     *
     * This method invokes <code>shutdown()</code>
     */
    public void close() {
      shutdown();
    }

}
