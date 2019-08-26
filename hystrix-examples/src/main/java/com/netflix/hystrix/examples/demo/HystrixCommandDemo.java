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
package com.netflix.hystrix.examples.demo;

import java.math.BigDecimal;
import java.net.HttpCookie;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.netflix.config.ConfigurationManager;
import com.netflix.hystrix.HystrixCommandKey;
import com.netflix.hystrix.HystrixCommandMetrics;
import com.netflix.hystrix.HystrixCommandMetrics.HealthCounts;
import com.netflix.hystrix.HystrixRequestLog;
import com.netflix.hystrix.strategy.concurrency.HystrixRequestContext;

/**
 * Executable client that demonstrates the lifecycle, metrics, request log and behavior of HystrixCommands.
 */
public class HystrixCommandDemo {

    public static void main(String args[]) {
        new HystrixCommandDemo().startDemo();
    }

    public HystrixCommandDemo() {
        /*
         * Instead of using injected properties we'll set them via Archaius
         * so the rest of the code behaves as it would in a real system
         * where it picks up properties externally provided.
         */
        ConfigurationManager.getConfigInstance().setProperty("hystrix.threadpool.default.coreSize", 8);
        ConfigurationManager.getConfigInstance().setProperty("hystrix.command.CreditCardCommand.execution.isolation.thread.timeoutInMilliseconds", 3000);
        ConfigurationManager.getConfigInstance().setProperty("hystrix.command.GetUserAccountCommand.execution.isolation.thread.timeoutInMilliseconds", 50);
        // set the rolling percentile more granular so we see data change every second rather than every 10 seconds as is the default 
        ConfigurationManager.getConfigInstance().setProperty("hystrix.command.default.metrics.rollingPercentile.numBuckets", 60);
    }

    /*
     * Thread-pool to simulate HTTP requests.
     * 
     * Use CallerRunsPolicy so we can just keep iterating and adding to it and it will block when full.
     */
    private final ThreadPoolExecutor pool = new ThreadPoolExecutor(5, 5, 5, TimeUnit.DAYS, new SynchronousQueue<Runnable>(), new ThreadPoolExecutor.CallerRunsPolicy());

    public void startDemo() {
        startMetricsMonitor();
        while (true) {
            // 模拟一个不中断的请求
            runSimulatedRequestOnThread();
        }
    }

    public void runSimulatedRequestOnThread() {
        pool.execute(new Runnable() {

            @Override
            public void run() {
                // 在执行 对应的 command 之后 总是 先初始化 绑定在本线程的 上下文对象    HystrixRequestContext 对象 默认是个空对象
                HystrixRequestContext context = HystrixRequestContext.initializeContext();
                try {
                    // 模拟用户 确认订单并付款的过程
                    executeSimulatedUserRequestForOrderConfirmationAndCreditCardPayment();

                    System.out.println("Request => " + HystrixRequestLog.getCurrentRequest().getExecutedCommandsAsString());
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    context.shutdown();
                }
            }

        });
    }

    public void executeSimulatedUserRequestForOrderConfirmationAndCreditCardPayment() throws InterruptedException, ExecutionException {
        /* fetch user object with http cookies */
        // hystrix 的使用方式 就是针对 每个 跨服务请求 都是用 command 对象进行包裹 这样就能实现隔离 不会因为某个请求延时  导致其他请求 被拖垮
        // 当前command 执行还是可能会抛出异常的
        // 因为下一步的 结果需要依赖这一步的结果 所以要执行调用execute() 也就是 queue().get()
        UserAccount user = new GetUserAccountCommand(new HttpCookie("mockKey", "mockValueFromHttpRequest")).execute();

        /* fetch the payment information (asynchronously) for the user so the credit card payment can proceed */
        Future<PaymentInformation> paymentInformation = new GetPaymentInformationCommand(user).queue();

        /* fetch the order we're processing for the user */
        int orderIdFromRequestArgument = 13579;
        // 获取订单结果
        Order previouslySavedOrder = new GetOrderCommand(orderIdFromRequestArgument).execute();

        CreditCardCommand credit = new CreditCardCommand(previouslySavedOrder, paymentInformation.get(), new BigDecimal(123.45));
        // 阻塞获取结果
        credit.execute();
    }

    public void startMetricsMonitor() {
        Thread t = new Thread(new Runnable() {

            @Override
            public void run() {
                while (true) {
                    /**
                     * Since this is a simple example and we know the exact HystrixCommandKeys we are interested in
                     * we will retrieve the HystrixCommandMetrics objects directly.
                     * 
                     * Typically you would instead retrieve metrics from where they are published which is by default
                     * done using Servo: https://github.com/Netflix/Hystrix/wiki/Metrics-and-Monitoring
                     */

                    // wait 5 seconds on each loop
                    try {
                        Thread.sleep(5000);
                    } catch (Exception e) {
                        // ignore
                    }

                    // we are using default names so can use class.getSimpleName() to derive the keys
                    // 开启统计  通过传入指定的 commandKey  一开始 在 metrics 对象中 还不存在  (使用 getInstance(commandKey) 尝试获取 没有的话是不会创建对象的)
                    HystrixCommandMetrics creditCardMetrics = HystrixCommandMetrics.getInstance(HystrixCommandKey.Factory.asKey(CreditCardCommand.class.getSimpleName()));
                    HystrixCommandMetrics orderMetrics = HystrixCommandMetrics.getInstance(HystrixCommandKey.Factory.asKey(GetOrderCommand.class.getSimpleName()));
                    HystrixCommandMetrics userAccountMetrics = HystrixCommandMetrics.getInstance(HystrixCommandKey.Factory.asKey(GetUserAccountCommand.class.getSimpleName()));
                    HystrixCommandMetrics paymentInformationMetrics = HystrixCommandMetrics.getInstance(HystrixCommandKey.Factory.asKey(GetPaymentInformationCommand.class.getSimpleName()));

                    // print out metrics
                    StringBuilder out = new StringBuilder();
                    out.append("\n");
                    out.append("#####################################################################################").append("\n");
                    out.append("# CreditCardCommand: " + getStatsStringFromMetrics(creditCardMetrics)).append("\n");
                    out.append("# GetOrderCommand: " + getStatsStringFromMetrics(orderMetrics)).append("\n");
                    out.append("# GetUserAccountCommand: " + getStatsStringFromMetrics(userAccountMetrics)).append("\n");
                    out.append("# GetPaymentInformationCommand: " + getStatsStringFromMetrics(paymentInformationMetrics)).append("\n");
                    out.append("#####################################################################################").append("\n");
                    System.out.println(out.toString());
                }
            }

            private String getStatsStringFromMetrics(HystrixCommandMetrics metrics) {
                StringBuilder m = new StringBuilder();
                // 当存在 测量对象后  因为 如果command 没有初始化  对应的 metrics 对象是不会被设置的 
                if (metrics != null) {
                    HealthCounts health = metrics.getHealthCounts();
                    m.append("Requests: ").append(health.getTotalRequests()).append(" ");
                    m.append("Errors: ").append(health.getErrorCount()).append(" (").append(health.getErrorPercentage()).append("%)   ");
                    m.append("Mean: ").append(metrics.getExecutionTimePercentile(50)).append(" ");
                    m.append("75th: ").append(metrics.getExecutionTimePercentile(75)).append(" ");
                    m.append("90th: ").append(metrics.getExecutionTimePercentile(90)).append(" ");
                    m.append("99th: ").append(metrics.getExecutionTimePercentile(99)).append(" ");
                }
                return m.toString();
            }

        });
        t.setDaemon(true);
        t.start();
    }
}
