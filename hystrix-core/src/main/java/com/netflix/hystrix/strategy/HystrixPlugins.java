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
package com.netflix.hystrix.strategy;

import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.netflix.hystrix.strategy.concurrency.HystrixConcurrencyStrategy;
import com.netflix.hystrix.strategy.concurrency.HystrixConcurrencyStrategyDefault;
import com.netflix.hystrix.strategy.eventnotifier.HystrixEventNotifier;
import com.netflix.hystrix.strategy.eventnotifier.HystrixEventNotifierDefault;
import com.netflix.hystrix.strategy.executionhook.HystrixCommandExecutionHook;
import com.netflix.hystrix.strategy.executionhook.HystrixCommandExecutionHookDefault;
import com.netflix.hystrix.strategy.metrics.HystrixMetricsPublisher;
import com.netflix.hystrix.strategy.metrics.HystrixMetricsPublisherDefault;
import com.netflix.hystrix.strategy.metrics.HystrixMetricsPublisherFactory;
import com.netflix.hystrix.strategy.properties.HystrixDynamicProperties;
import com.netflix.hystrix.strategy.properties.HystrixDynamicPropertiesSystemProperties;
import com.netflix.hystrix.strategy.properties.HystrixPropertiesStrategy;
import com.netflix.hystrix.strategy.properties.HystrixPropertiesStrategyDefault;

/**
 * Registry for plugin implementations that allows global override and handles the retrieval of correct implementation based on order of precedence:
 * <ol>
 * <li>plugin registered globally via <code>register</code> methods in this class</li>
 * <li>plugin registered and retrieved using the resolved {@link HystrixDynamicProperties} (usually Archaius, see get methods for property names)</li>
 * <li>plugin registered and retrieved using the JDK {@link ServiceLoader}</li>
 * <li>default implementation</li>
 * </ol>
 * 
 * The exception to the above order is the {@link HystrixDynamicProperties} implementation 
 * which is only loaded through <code>System.properties</code> or the ServiceLoader (see the {@link HystrixPlugins#getDynamicProperties() getter} for more details).
 * <p>
 * See the Hystrix GitHub Wiki for more information: <a href="https://github.com/Netflix/Hystrix/wiki/Plugins">https://github.com/Netflix/Hystrix/wiki/Plugins</a>.
 * 熔断器 插件类
 */
public class HystrixPlugins {
    
    //We should not load unless we are requested to. This avoids accidental initialization. @agentgt
    //See https://en.wikipedia.org/wiki/Initialization-on-demand_holder_idiom

    /**
     * 当引用该实例对象 后 静态域才会触发对象的创建
     */
    private static class LazyHolder { private static final HystrixPlugins INSTANCE = HystrixPlugins.create(); }

    /**
     * 类加载器
     */
    private final ClassLoader classLoader;
    /* 事件通知对象 */ final AtomicReference<HystrixEventNotifier> notifier = new AtomicReference<HystrixEventNotifier>();
    /* 并发策略 */ final AtomicReference<HystrixConcurrencyStrategy> concurrencyStrategy = new AtomicReference<HystrixConcurrencyStrategy>();
    /* 熔断测量发布者 */ final AtomicReference<HystrixMetricsPublisher> metricsPublisher = new AtomicReference<HystrixMetricsPublisher>();
    /* 就是维护了一些可以获取属性的方法 */ final AtomicReference<HystrixPropertiesStrategy> propertiesFactory = new AtomicReference<HystrixPropertiesStrategy>();
    /* 钩子对象 */ final AtomicReference<HystrixCommandExecutionHook> commandExecutionHook = new AtomicReference<HystrixCommandExecutionHook>();
    /**
     * 该接口 基于 SystemProp 的实现就是去环境变量中获取需要的属性
     */
    private final HystrixDynamicProperties dynamicProperties;

    /**
     * 创建插件对象
     * @param classLoader
     * @param logSupplier
     */
    private HystrixPlugins(ClassLoader classLoader, LoggerSupplier logSupplier) {
        //This will load Archaius if its in the classpath.
        // 设置 classLoader
        this.classLoader = classLoader;
        //N.B. Do not use a logger before this is loaded as it will most likely load the configuration system.
        //The configuration system may need to do something prior to loading logging. @agentgt
        // 解析动态属性
        dynamicProperties = resolveDynamicProperties(classLoader, logSupplier);
    }

    /**
     * For unit test purposes.
     * @ExcludeFromJavadoc
     */
    /* private */ static HystrixPlugins create(ClassLoader classLoader, LoggerSupplier logSupplier) {
        return new HystrixPlugins(classLoader, logSupplier);
    }
    
    /**
     * For unit test purposes.
     * @ExcludeFromJavadoc
     */
    /* private */ static HystrixPlugins create(ClassLoader classLoader) {
        // 创建一个插件对象
        return new HystrixPlugins(classLoader, new LoggerSupplier() {
            @Override
            public Logger getLogger() {
                return LoggerFactory.getLogger(HystrixPlugins.class);
            }
        });
    }
    /**
     * 创建一个插件对象
     * @ExcludeFromJavadoc
     */
    /* private */ static HystrixPlugins create() {
        return create(HystrixPlugins.class.getClassLoader());
    }

    public static HystrixPlugins getInstance() {
        return LazyHolder.INSTANCE;
    }

    /**
     * Reset all of the HystrixPlugins to null.  You may invoke this directly, or it also gets invoked via <code>Hystrix.reset()</code>
     * 重置 就是将维护的各个原子引用置空
     */
    public static void reset() {
        getInstance().notifier.set(null);
        getInstance().concurrencyStrategy.set(null);
        getInstance().metricsPublisher.set(null);
        getInstance().propertiesFactory.set(null);
        getInstance().commandExecutionHook.set(null);
        HystrixMetricsPublisherFactory.reset();
    }

    /**
     * Retrieve instance of {@link HystrixEventNotifier} to use based on order of precedence as defined in {@link HystrixPlugins} class header.
     * <p>
     * Override default by using {@link #registerEventNotifier(HystrixEventNotifier)} or setting property (via Archaius): <code>hystrix.plugin.HystrixEventNotifier.implementation</code> with the full classname to
     * load.
     * 
     * @return {@link HystrixEventNotifier} implementation to use
     * 获取事件通知器对象
     */
    public HystrixEventNotifier getEventNotifier() {
        // 当通知器对象还没有生成的时候
        if (notifier.get() == null) {
            // check for an implementation from Archaius first
            // 应该是从某个地方寻找该抽象类的全部实现类
            Object impl = getPluginImplementation(HystrixEventNotifier.class);
            if (impl == null) {
                // nothing set via Archaius so initialize with default
                notifier.compareAndSet(null, HystrixEventNotifierDefault.getInstance());
                // we don't return from here but call get() again in case of thread-race so the winner will always get returned
            } else {
                // we received an implementation from Archaius so use it
                notifier.compareAndSet(null, (HystrixEventNotifier) impl);
            }
        }
        return notifier.get();
    }

    /**
     * Register a {@link HystrixEventNotifier} implementation as a global override of any injected or default implementations.
     * 
     * @param impl
     *            {@link HystrixEventNotifier} implementation
     * @throws IllegalStateException
     *             if called more than once or after the default was initialized (if usage occurs before trying to register)
     *             注册事件监听器 如果 CAS 操作失败 提示被其他线程抢占注册
     */
    public void registerEventNotifier(HystrixEventNotifier impl) {
        if (!notifier.compareAndSet(null, impl)) {
            throw new IllegalStateException("Another strategy was already registered.");
        }
    }

    /**
     * Retrieve instance of {@link HystrixConcurrencyStrategy} to use based on order of precedence as defined in {@link HystrixPlugins} class header.
     * <p>
     * Override default by using {@link #registerConcurrencyStrategy(HystrixConcurrencyStrategy)} or setting property (via Archaius): <code>hystrix.plugin.HystrixConcurrencyStrategy.implementation</code> with the
     * full classname to load.
     * 
     * @return {@link HystrixConcurrencyStrategy} implementation to use
     * 获取并发策略  内部就是跟线程池相关的属性
     */
    public HystrixConcurrencyStrategy getConcurrencyStrategy() {
        if (concurrencyStrategy.get() == null) {
            // check for an implementation from Archaius first
            Object impl = getPluginImplementation(HystrixConcurrencyStrategy.class);
            if (impl == null) {
                // nothing set via Archaius so initialize with default
                concurrencyStrategy.compareAndSet(null, HystrixConcurrencyStrategyDefault.getInstance());
                // we don't return from here but call get() again in case of thread-race so the winner will always get returned
            } else {
                // we received an implementation from Archaius so use it
                concurrencyStrategy.compareAndSet(null, (HystrixConcurrencyStrategy) impl);
            }
        }
        return concurrencyStrategy.get();
    }

    /**
     * Register a {@link HystrixConcurrencyStrategy} implementation as a global override of any injected or default implementations.
     * 
     * @param impl
     *            {@link HystrixConcurrencyStrategy} implementation
     * @throws IllegalStateException
     *             if called more than once or after the default was initialized (if usage occurs before trying to register)
     *             设置当前并发策略
     */
    public void registerConcurrencyStrategy(HystrixConcurrencyStrategy impl) {
        if (!concurrencyStrategy.compareAndSet(null, impl)) {
            throw new IllegalStateException("Another strategy was already registered.");
        }
    }

    /**
     * Retrieve instance of {@link HystrixMetricsPublisher} to use based on order of precedence as defined in {@link HystrixPlugins} class header.
     * <p>
     * Override default by using {@link #registerMetricsPublisher(HystrixMetricsPublisher)} or setting property (via Archaius): <code>hystrix.plugin.HystrixMetricsPublisher.implementation</code> with the full
     * classname to load.
     * 
     * @return {@link HystrixMetricsPublisher} implementation to use
     * 获取测量工具
     */
    public HystrixMetricsPublisher getMetricsPublisher() {
        if (metricsPublisher.get() == null) {
            // check for an implementation from Archaius first
            Object impl = getPluginImplementation(HystrixMetricsPublisher.class);
            if (impl == null) {
                // nothing set via Archaius so initialize with default
                metricsPublisher.compareAndSet(null, HystrixMetricsPublisherDefault.getInstance());
                // we don't return from here but call get() again in case of thread-race so the winner will always get returned
            } else {
                // we received an implementation from Archaius so use it
                metricsPublisher.compareAndSet(null, (HystrixMetricsPublisher) impl);
            }
        }
        return metricsPublisher.get();
    }

    /**
     * Register a {@link HystrixMetricsPublisher} implementation as a global override of any injected or default implementations.
     * 
     * @param impl
     *            {@link HystrixMetricsPublisher} implementation
     * @throws IllegalStateException
     *             if called more than once or after the default was initialized (if usage occurs before trying to register)
     */
    public void registerMetricsPublisher(HystrixMetricsPublisher impl) {
        if (!metricsPublisher.compareAndSet(null, impl)) {
            throw new IllegalStateException("Another strategy was already registered.");
        }
    }

    /**
     * Retrieve instance of {@link HystrixPropertiesStrategy} to use based on order of precedence as defined in {@link HystrixPlugins} class header.
     * <p>
     * Override default by using {@link #registerPropertiesStrategy(HystrixPropertiesStrategy)} or setting property (via Archaius): <code>hystrix.plugin.HystrixPropertiesStrategy.implementation</code> with the full
     * classname to load.
     * 
     * @return {@link HystrixPropertiesStrategy} implementation to use
     * 设置 属性策略对象
     */
    public HystrixPropertiesStrategy getPropertiesStrategy() {
        if (propertiesFactory.get() == null) {
            // check for an implementation from Archaius first
            // 尝试加载 属性策略对象
            Object impl = getPluginImplementation(HystrixPropertiesStrategy.class);
            if (impl == null) {
                // nothing set via Archaius so initialize with default
                // 既没有从动态工厂中查询到 属性策略实现类 也没有从 SPI 中加载 就使用默认的 属性策略实现类
                propertiesFactory.compareAndSet(null, HystrixPropertiesStrategyDefault.getInstance());
                // we don't return from here but call get() again in case of thread-race so the winner will always get returned
            } else {
                // we received an implementation from Archaius so use it
                propertiesFactory.compareAndSet(null, (HystrixPropertiesStrategy) impl);
            }
        }
        return propertiesFactory.get();
    }
    
    /**
     * Retrieves the instance of {@link HystrixDynamicProperties} to use.
     * <p>
     * Unlike the other plugins this plugin cannot be re-registered and is only loaded at creation 
     * of the {@link HystrixPlugins} singleton.
     * <p>
     * The order of precedence for loading implementations is:
     * <ol>
     * <li>System property of key: <code>hystrix.plugin.HystrixDynamicProperties.implementation</code> with the class as a value.</li>
     * <li>The {@link ServiceLoader}.</li>
     * <li>An implementation based on Archaius if it is found in the classpath is used.</li>
     * <li>A fallback implementation based on the {@link System#getProperties()}</li>
     * </ol>
     * @return never <code>null</code>
     * 获取动态属性对象
     */
    public HystrixDynamicProperties getDynamicProperties() {
        return dynamicProperties;
    }

    /**
     * Register a {@link HystrixPropertiesStrategy} implementation as a global override of any injected or default implementations.
     * 
     * @param impl
     *            {@link HystrixPropertiesStrategy} implementation
     * @throws IllegalStateException
     *             if called more than once or after the default was initialized (if usage occurs before trying to register)
     */
    public void registerPropertiesStrategy(HystrixPropertiesStrategy impl) {
        if (!propertiesFactory.compareAndSet(null, impl)) {
            throw new IllegalStateException("Another strategy was already registered.");
        }
    }

    /**
     * Retrieve instance of {@link HystrixCommandExecutionHook} to use based on order of precedence as defined in {@link HystrixPlugins} class header.
     * <p>
     * Override default by using {@link #registerCommandExecutionHook(HystrixCommandExecutionHook)} or setting property (via Archaius): <code>hystrix.plugin.HystrixCommandExecutionHook.implementation</code> with the
     * full classname to
     * load.
     * 
     * @return {@link HystrixCommandExecutionHook} implementation to use
     * 
     * @since 1.2
     * 获取 hystrix Command 的 执行钩子
     */
    public HystrixCommandExecutionHook getCommandExecutionHook() {
        if (commandExecutionHook.get() == null) {
            // check for an implementation from Archaius first
            Object impl = getPluginImplementation(HystrixCommandExecutionHook.class);
            if (impl == null) {
                // nothing set via Archaius so initialize with default
                commandExecutionHook.compareAndSet(null, HystrixCommandExecutionHookDefault.getInstance());
                // we don't return from here but call get() again in case of thread-race so the winner will always get returned
            } else {
                // we received an implementation from Archaius so use it
                commandExecutionHook.compareAndSet(null, (HystrixCommandExecutionHook) impl);
            }
        }
        return commandExecutionHook.get();
    }

    /**
     * Register a {@link HystrixCommandExecutionHook} implementation as a global override of any injected or default implementations.
     * 
     * @param impl
     *            {@link HystrixCommandExecutionHook} implementation
     * @throws IllegalStateException
     *             if called more than once or after the default was initialized (if usage occurs before trying to register)
     * 
     * @since 1.2
     */
    public void registerCommandExecutionHook(HystrixCommandExecutionHook impl) {
        if (!commandExecutionHook.compareAndSet(null, impl)) {
            throw new IllegalStateException("Another strategy was already registered.");
        }
    }


    /**
     * 获取插件的实现类
     * @param pluginClass
     * @param <T>
     * @return
     */
    private <T> T getPluginImplementation(Class<T> pluginClass) {
        // 根据传入的抽象类类型 以及从环境变量中抽取的属性来初始化
        T p = getPluginImplementationViaProperties(pluginClass, dynamicProperties);
        if (p != null) return p;
        // 借助 SPI 机制初始化对象
        return findService(pluginClass, classLoader);
    }

    /**
     * 获取插件实现类属性
     * @param pluginClass   接口类型
     * @param dynamicProperties   实现类
     * @param <T>
     * @return
     */
    @SuppressWarnings("unchecked")
    private static <T> T getPluginImplementationViaProperties(Class<T> pluginClass, HystrixDynamicProperties dynamicProperties) {
        // 获取插件类名称
        String classSimpleName = pluginClass.getSimpleName();
        // Check Archaius for plugin class.
        // 首先尝试从系统变量中获取 plugin 的实现类
        String propertyName = "hystrix.plugin." + classSimpleName + ".implementation";
        // 先尝试从动态工厂中寻找抽象类 默认情况 抽象工厂是从 hystrix 的配置中心获取的
        String implementingClass = dynamicProperties.getString(propertyName, null).get();
        // 存在的情况下 通过反射调用 这里获得实际类型后 要通过强转 返回接口类型
        if (implementingClass != null) {
            try {
                Class<?> cls = Class.forName(implementingClass);
                // narrow the scope (cast) to the type we're expecting
                cls = cls.asSubclass(pluginClass);
                return (T) cls.newInstance();
            } catch (ClassCastException e) {
                throw new RuntimeException(classSimpleName + " implementation is not an instance of " + classSimpleName + ": " + implementingClass);
            } catch (ClassNotFoundException e) {
                throw new RuntimeException(classSimpleName + " implementation class not found: " + implementingClass, e);
            } catch (InstantiationException e) {
                throw new RuntimeException(classSimpleName + " implementation not able to be instantiated: " + implementingClass, e);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(classSimpleName + " implementation not able to be accessed: " + implementingClass, e);
            }
        } else {
            return null;
        }
    }


    /**
     * 传入 classLoader 解析动态属性
     * @param classLoader
     * @param logSupplier
     * @return
     */
    private static HystrixDynamicProperties resolveDynamicProperties(ClassLoader classLoader, LoggerSupplier logSupplier) {
        // 这里传入了 指定的插件实现类 传入的动态属性对象是从 system 变量中获得的
        HystrixDynamicProperties hp = getPluginImplementationViaProperties(HystrixDynamicProperties.class, 
                HystrixDynamicPropertiesSystemProperties.getInstance());
        if (hp != null) {
            logSupplier.getLogger().debug(
                    "Created HystrixDynamicProperties instance from System property named "
                    + "\"hystrix.plugin.HystrixDynamicProperties.implementation\". Using class: {}", 
                    hp.getClass().getCanonicalName());
            return hp;
        }
        // 没有从环境变量中 生成 hystrixDynamicProp 所以从SPI中加载
        hp = findService(HystrixDynamicProperties.class, classLoader);
        if (hp != null) {
            logSupplier.getLogger()
                    .debug("Created HystrixDynamicProperties instance by loading from ServiceLoader. Using class: {}", 
                            hp.getClass().getCanonicalName());
            return hp;
        }
        // 使用 动态配置工厂获取配置
        hp = HystrixArchaiusHelper.createArchaiusDynamicProperties();
        if (hp != null) {
            logSupplier.getLogger().debug("Created HystrixDynamicProperties. Using class : {}", 
                    hp.getClass().getCanonicalName());
            return hp;
        }
        hp = HystrixDynamicPropertiesSystemProperties.getInstance();
        logSupplier.getLogger().info("Using System Properties for HystrixDynamicProperties! Using class: {}", 
                hp.getClass().getCanonicalName());
        return hp;
    }

    /**
     * 使用SPI 机制 加载serviceImpl
     * @param spi
     * @param classLoader
     * @param <T>
     * @return
     * @throws ServiceConfigurationError
     */
    private static <T> T findService(
            Class<T> spi, 
            ClassLoader classLoader) throws ServiceConfigurationError {
        
        ServiceLoader<T> sl = ServiceLoader.load(spi,
                classLoader);
        for (T s : sl) {
            if (s != null)
                return s;
        }
        return null;
    }
    
    interface LoggerSupplier {
        Logger getLogger();
    }


}
