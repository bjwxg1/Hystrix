package com.netflix.hystrix.examples.learn_demo;

import com.netflix.hystrix.HystrixCommand;
import com.netflix.hystrix.HystrixCommandGroupKey;

/**
 * @Descriprion:
 * @Author:wuxiaoguang@58.com
 * @Date：created in 2020/1/14
 */
public class FailSilentCommand extends HystrixCommand<String> {
    private final boolean throwException;

    public FailSilentCommand(boolean throwException) {
        super(HystrixCommandGroupKey.Factory.asKey("ExampleGroup"));
        this.throwException = throwException;
    }

    @Override
    protected String run() {
        if (throwException) {
            throw new RuntimeException("failure from FailSilentCommand");
        } else {
            return "success";
        }
    }

    @Override
    protected String getFallback() {
        return null;
    }

    public static void main(String[] args) {
        System.err.println(new FailSilentCommand(true).execute());
        System.err.println(new FailSilentCommand(false).execute());
    }
}
