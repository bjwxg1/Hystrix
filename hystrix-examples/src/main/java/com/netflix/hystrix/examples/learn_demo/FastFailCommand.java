package com.netflix.hystrix.examples.learn_demo;

import com.netflix.hystrix.HystrixCommand;
import com.netflix.hystrix.HystrixCommandGroupKey;

/**
 * @Descriprion:
 * @Author:wuxiaoguang@58.com
 * @Date：created in 2020/1/14
 */
public class FastFailCommand  extends HystrixCommand<String> {
    private final boolean throwException;

    public FastFailCommand(boolean throwException) {
        super(HystrixCommandGroupKey.Factory.asKey("ExampleGroup"));
        this.throwException = throwException;
    }

    @Override
    protected String run() {
        if (throwException) {
            throw new RuntimeException("failure from FastFailCommand");
        } else {
            return "success";
        }
    }

    public static void main(String[] args) {
        System.err.println(new FastFailCommand(false).execute());
        try {
            new FastFailCommand(true).execute();
        } catch (Exception e) {
            System.err.println(e.getCause());
            e.printStackTrace();
        }
    }
}
