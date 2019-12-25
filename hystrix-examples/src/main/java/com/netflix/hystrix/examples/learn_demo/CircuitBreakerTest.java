package com.netflix.hystrix.examples.learn_demo;

import com.netflix.hystrix.*;
import org.junit.Test;

/**
 * @Descriprion:
 * @Author:wuxiaoguang@58.com
 * @Date：created in 2019/11/28
 */
public class CircuitBreakerTest {
    public static int num = 0;
    static HystrixCommand.Setter setter = HystrixCommand.Setter
            .withGroupKey(HystrixCommandGroupKey.Factory.asKey("circuitBreakerTestGroup"))
            .andCommandKey(HystrixCommandKey.Factory.asKey("circuitBreakerTestCommand"))
            .andThreadPoolKey(HystrixThreadPoolKey.Factory.asKey("circuitBreakerTestPool"))
            .andThreadPoolPropertiesDefaults(HystrixThreadPoolProperties.Setter().withCoreSize(10)) // 配置线程池
            .andCommandPropertiesDefaults(HystrixCommandProperties.Setter()
                    .withCircuitBreakerEnabled(true)
                    .withCircuitBreakerRequestVolumeThreshold(10)
                    .withCircuitBreakerErrorThresholdPercentage(80));
    // 未配置的值均取系统默认值
    HystrixCommand<Object> hystrixCommand = new HystrixCommand<Object>(setter) {

        @Override
        protected Object run() throws Exception {
            if (num % 2 == 0) {
                return num + "";
            } else {
                int j = 0;
                while (true) {
                    j++; // 死循环模拟超时
                }
            }
        }

        @Override
        protected Object getFallback() {
            System.out.println(getExecutionException());
            return "CircuitBreaker fallback:" + num;
        }
    };


    @Test
    public void testCircuitBreaker() throws Exception {
        for (int i = 0; i < 30; i++) {
            CircuitBreakerTest.num = i;
            CircuitBreakerTest circuitBreakerTest = new CircuitBreakerTest();
            String result = (String) circuitBreakerTest.hystrixCommand.execute();
            System.out.println(result);
        }
    }
}
