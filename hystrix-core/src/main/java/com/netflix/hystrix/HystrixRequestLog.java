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
package com.netflix.hystrix;

import com.netflix.hystrix.metric.HystrixRequestEventsStream;
import com.netflix.hystrix.strategy.HystrixPlugins;
import com.netflix.hystrix.strategy.concurrency.HystrixConcurrencyStrategy;
import com.netflix.hystrix.strategy.concurrency.HystrixRequestContext;
import com.netflix.hystrix.strategy.concurrency.HystrixRequestVariableHolder;
import com.netflix.hystrix.strategy.concurrency.HystrixRequestVariableLifecycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Log of {@link HystrixCommand} executions and events during the current request.
 * hystrix 请求日志对象
 */
public class HystrixRequestLog {
    private static final Logger logger = LoggerFactory.getLogger(HystrixRequestLog.class);

    /**
     * RequestLog: Reduce Chance of Memory Leak
     * https://github.com/Netflix/Hystrix/issues/53
     * 
     * Upper limit on RequestLog before ignoring further additions and logging warnings.
     * 
     * Intended to help prevent memory leaks when someone isn't aware of the
     * HystrixRequestContext lifecycle or enabling/disabling RequestLog.
     * 最大存储容量 允许暂存的 日志数量
     */
    /* package */static final int MAX_STORAGE = 1000;

    /**
     * 一个缓存对象 当不存在数据时 会调用 init 方法 初始化 对象  该对象用于初始化 RequestVariable
     */
    private static final HystrixRequestVariableHolder<HystrixRequestLog> currentRequestLog = new HystrixRequestVariableHolder<HystrixRequestLog>(new HystrixRequestVariableLifecycle<HystrixRequestLog>() {
        @Override
        public HystrixRequestLog initialValue() {
            // 初始化一个 HystrixRequestLog
            return new HystrixRequestLog();
        }

        /**
         * 当销毁对象时 调用的方法
         * @param value
         *            of request variable to allow cleanup activity.
         *            <p>
         *            If nothing needs to be cleaned up then nothing needs to be done in this method.
         */
        public void shutdown(HystrixRequestLog value) {
            //write this value to the Request stream
            // 将所有执行的 事件写入到 事件流中
            HystrixRequestEventsStream.getInstance().write(value.getAllExecutedCommands());
        }
    });

    /**
     * History of {@link HystrixCommand} executed in this request.
     * 记录了 本次请求 执行的 command
     */
    private LinkedBlockingQueue<HystrixCommand<?>> executedCommands = new LinkedBlockingQueue<HystrixCommand<?>>(MAX_STORAGE);

    /**
     * History of {@link HystrixInvokableInfo} executed in this request.
     * 记录本次调用信息
     */
    private LinkedBlockingQueue<HystrixInvokableInfo<?>> allExecutedCommands = new LinkedBlockingQueue<HystrixInvokableInfo<?>>(MAX_STORAGE);

    // prevent public instantiation
    private HystrixRequestLog() {
    }

    /**
     * {@link HystrixRequestLog} for current request as defined by {@link HystrixRequestContext}.
     * 
     * @return {@link HystrixRequestLog}
     * 通过传入 并发策略 生成 日志对象
     */
    public static HystrixRequestLog getCurrentRequest(HystrixConcurrencyStrategy concurrencyStrategy) {
        return currentRequestLog.get(concurrencyStrategy);
    }

    /**
     * {@link HystrixRequestLog} for current request as defined by {@link HystrixRequestContext}.
     * <p>
     * NOTE: This uses the default {@link HystrixConcurrencyStrategy} or global override. If an injected strategy is being used by commands you must instead use
     * {@link #getCurrentRequest(HystrixConcurrencyStrategy)}.
     * 
     * @return {@link HystrixRequestLog}
     * 通过默认的并发策略对象来生成 日志对象
     */
    public static HystrixRequestLog getCurrentRequest() {
        // hystrix 代表 从 系统变量 -> SPI 等 依次加载 属性并根据属性来生成对应的 配置类 比如 并发策略对象
        // 最后就是调用  currentRequestLog.init
        return currentRequestLog.get(HystrixPlugins.getInstance().getConcurrencyStrategy());
    }

    /**
     * Retrieve {@link HystrixCommand} instances that were executed during this {@link HystrixRequestContext}.
     * 
     * @return {@code Collection<HystrixCommand<?>>}
     */
    @Deprecated
    public Collection<HystrixCommand<?>> getExecutedCommands() {
        return Collections.unmodifiableCollection(executedCommands);
    }

    /**
     * Retrieve {@link HystrixCommand} instances that were executed during this {@link HystrixRequestContext}.
     * 
     * @return {@code Collection<HystrixCommand<?>>}
     * 获取所有执行过的命令信息
     */
    public Collection<HystrixInvokableInfo<?>> getAllExecutedCommands() {
        return Collections.unmodifiableCollection(allExecutedCommands);
    }

    /**
     * Add {@link HystrixCommand} instance to the request log.
     * 
     * @param command
     *            {@code HystrixCommand<?>}
     *            增加执行过的命令
     */
    /* package */void addExecutedCommand(HystrixInvokableInfo<?> command) {
        // 添加失败 代表超过容量了  这里只是打印日志
        if (!allExecutedCommands.offer(command)) {
            // see RequestLog: Reduce Chance of Memory Leak https://github.com/Netflix/Hystrix/issues/53
            logger.warn("RequestLog ignoring command after reaching limit of " + MAX_STORAGE + ". See https://github.com/Netflix/Hystrix/issues/53 for more information.");
        }

        // TODO remove this when deprecation completed
        // 如果 command 是  HystrixCommand 的话 就 尝试设置到 executedCommand 对象中
        if (command instanceof HystrixCommand) {
            @SuppressWarnings("rawtypes")
            HystrixCommand<?> _c = (HystrixCommand) command;
            if (!executedCommands.offer(_c)) {
                // see RequestLog: Reduce Chance of Memory Leak https://github.com/Netflix/Hystrix/issues/53
                logger.warn("RequestLog ignoring command after reaching limit of " + MAX_STORAGE + ". See https://github.com/Netflix/Hystrix/issues/53 for more information.");
            }
        }
    }

    /**
     * Formats the log of executed commands into a string usable for logging purposes.
     * <p>
     * Examples:
     * <ul>
     * <li>TestCommand[SUCCESS][1ms]</li>
     * <li>TestCommand[SUCCESS][1ms], TestCommand[SUCCESS, RESPONSE_FROM_CACHE][1ms]x4</li>
     * <li>TestCommand[TIMEOUT][1ms]</li>
     * <li>TestCommand[FAILURE][1ms]</li>
     * <li>TestCommand[THREAD_POOL_REJECTED][1ms]</li>
     * <li>TestCommand[THREAD_POOL_REJECTED, FALLBACK_SUCCESS][1ms]</li>
     * <li>TestCommand[EMIT, SUCCESS][1ms]</li>
     * <li>TestCommand[EMITx5, SUCCESS][1ms]</li>
     * <li>TestCommand[EMITx5, FAILURE, FALLBACK_EMITx6, FALLBACK_FAILURE][100ms]</li>
     * <li>TestCommand[FAILURE, FALLBACK_SUCCESS][1ms], TestCommand[FAILURE, FALLBACK_SUCCESS, RESPONSE_FROM_CACHE][1ms]x4</li>
     * <li>GetData[SUCCESS][1ms], PutData[SUCCESS][1ms], GetValues[SUCCESS][1ms], GetValues[SUCCESS, RESPONSE_FROM_CACHE][1ms], TestCommand[FAILURE, FALLBACK_FAILURE][1ms], TestCommand[FAILURE,
     * FALLBACK_FAILURE, RESPONSE_FROM_CACHE][1ms]</li>
     * </ul>
     * <p>
     * If a command has a multiplier such as <code>x4</code>, that means this command was executed 4 times with the same events. The time in milliseconds is the sum of the 4 executions.
     * <p>
     * For example, <code>TestCommand[SUCCESS][15ms]x4</code> represents TestCommand being executed 4 times and the sum of those 4 executions was 15ms. These 4 each executed the run() method since
     * <code>RESPONSE_FROM_CACHE</code> was not present as an event.
     *
     * If an EMIT or FALLBACK_EMIT has a multiplier such as <code>x5</code>, that means a <code>HystrixObservableCommand</code> was used and it emitted that number of <code>OnNext</code>s.
     * <p>
     * For example, <code>TestCommand[EMITx5, FAILURE, FALLBACK_EMITx6, FALLBACK_FAILURE][100ms]</code> represents TestCommand executing observably, emitted 5 <code>OnNext</code>s, then an <code>OnError</code>.
     * This command also has an Observable fallback, and it emits 6 <code>OnNext</code>s, then an <code>OnCompleted</code>.
     *
     * @return String request log or "Unknown" if unable to instead of throwing an exception.
     * 获取 本次执行过的执行对象  核心就是将 执行的 command 生成字符串后返回
     */
    public String getExecutedCommandsAsString() {
        try {
            LinkedHashMap<String, Integer> aggregatedCommandsExecuted = new LinkedHashMap<String, Integer>();
            Map<String, Integer> aggregatedCommandExecutionTime = new HashMap<String, Integer>();

            StringBuilder builder = new StringBuilder();
            int estimatedLength = 0;
            // 遍历所有执行的指令
            for (HystrixInvokableInfo<?> command : allExecutedCommands) {
                builder.setLength(0);
                builder.append(command.getCommandKey().name());

                // 每个命令执行的 事件类型
                List<HystrixEventType> events = new ArrayList<HystrixEventType>(command.getExecutionEvents());
                if (events.size() > 0) {
                    Collections.sort(events);
                    //replicate functionality of Arrays.toString(events.toArray()) to append directly to existing StringBuilder
                    builder.append("[");
                    for (HystrixEventType event : events) {
                        switch (event) {
                            case EMIT:
                                int numEmissions = command.getNumberEmissions();
                                if (numEmissions > 1) {
                                    builder.append(event).append("x").append(numEmissions).append(", ");
                                } else {
                                    builder.append(event).append(", ");
                                }
                                break;
                            case FALLBACK_EMIT:
                                int numFallbackEmissions = command.getNumberFallbackEmissions();
                                if (numFallbackEmissions > 1) {
                                    builder.append(event).append("x").append(numFallbackEmissions).append(", ");
                                } else {
                                    builder.append(event).append(", ");
                                }
                                break;
                            default:
                                builder.append(event).append(", ");
                        }
                    }
                    builder.setCharAt(builder.length() - 2, ']');
                    builder.setLength(builder.length() - 1);
                } else {
                    builder.append("[Executed]");
                }

                String display = builder.toString();
                estimatedLength += display.length() + 12; //add 12 chars to display length for appending totalExecutionTime and count below
                Integer counter = aggregatedCommandsExecuted.get(display);
                if( counter != null){
                    aggregatedCommandsExecuted.put(display, counter + 1);
                } else {
                    // add it
                    aggregatedCommandsExecuted.put(display, 1);
                }                

                int executionTime = command.getExecutionTimeInMilliseconds();
                if (executionTime < 0) {
                    // do this so we don't create negative values or subtract values
                    executionTime = 0;
                }
                counter = aggregatedCommandExecutionTime.get(display);
                if( counter != null && executionTime > 0){
                    // add to the existing executionTime (sum of executionTimes for duplicate command displayNames)
                    aggregatedCommandExecutionTime.put(display, aggregatedCommandExecutionTime.get(display) + executionTime);
                } else {
                    // add it
                    aggregatedCommandExecutionTime.put(display, executionTime);
                }

            }

            builder.setLength(0);
            builder.ensureCapacity(estimatedLength);
            for (String displayString : aggregatedCommandsExecuted.keySet()) {
                if (builder.length() > 0) {
                    builder.append(", ");
                }
                builder.append(displayString);

                int totalExecutionTime = aggregatedCommandExecutionTime.get(displayString);
                builder.append("[").append(totalExecutionTime).append("ms]");

                int count = aggregatedCommandsExecuted.get(displayString);
                if (count > 1) {
                    builder.append("x").append(count);
                }
            }
            return builder.toString();
        } catch (Exception e) {
            logger.error("Failed to create HystrixRequestLog response header string.", e);
            // don't let this cause the entire app to fail so just return "Unknown"
            return "Unknown";
        }
    }

}
