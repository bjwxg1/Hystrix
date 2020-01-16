package com.netflix.hystrix.examples.learn_demo;

import com.netflix.hystrix.HystrixCommand;
import com.netflix.hystrix.HystrixCommandGroupKey;
import com.netflix.hystrix.HystrixCommandKey;
import com.netflix.hystrix.strategy.concurrency.HystrixRequestContext;

/**
 * @Descriprion:
 * @Author:wuxiaoguang@58.com
 * @Date：created in 2020/1/15
 */
public class CacheCommand extends HystrixCommand<String> {
    private String key;
    private static final HystrixCommandKey commandKey = HystrixCommandKey.
            Factory.asKey("CacheCommand");
    public CacheCommand(String key) {
        super(Setter.withGroupKey(HystrixCommandGroupKey.Factory.asKey("CacheCommand"))
                .andCommandKey(commandKey));
        this.key = key;
    }
    @Override
    protected String run() throws Exception { return "hello" + key; }
    @Override
    protected String getCacheKey() {
        return key;
    }
    public static void main(String[] args) {
        HystrixRequestContext context=HystrixRequestContext.initializeContext();;
        try {
            CacheCommand command1 = new CacheCommand("hello");
            CacheCommand command2 = new CacheCommand("hello");
            command1.execute();
            System.err.println(command2.isResponseFromCache());
        } finally {
            context.shutdown();
        }
    }
}
