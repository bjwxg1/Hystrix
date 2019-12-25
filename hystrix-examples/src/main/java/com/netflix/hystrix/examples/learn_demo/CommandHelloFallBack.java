package com.netflix.hystrix.examples.learn_demo;

import com.netflix.hystrix.HystrixCommand;
import com.netflix.hystrix.HystrixCommandGroupKey;
import com.netflix.hystrix.HystrixCommandKey;
import org.junit.Test;

/**
 * @Descriprion:
 * @Author:wuxiaoguang@58.com
 * @Date：created in 2019/11/28
 */
public class CommandHelloFallBack extends HystrixCommand<String> {

    private String name;

    public void setName(String name) {
        this.name = name;
    }

    public CommandHelloFallBack() {
       // super(HystrixCommandGroupKey.Factory.asKey("ExampleGroup"));
        super(Setter.withGroupKey(HystrixCommandGroupKey.Factory.asKey("ExampleGroup"))
                .andCommandKey(HystrixCommandKey.Factory.asKey("HelloWorld")));
        this.name = "are you ok?";
    }

    @Override
    protected String run() throws Exception {
        System.out.println("Thread Name:" + Thread.currentThread().getName());
        Thread.sleep(1000*20);
        //throw new Exception("hello, are you ok?");
        return "Hello " + name + "!";
    }

    @Override
    protected String getFallback() {
        System.out.println("Thread Name:" + Thread.currentThread().getName());
        return "Hello Failure!!!";
    }

    @Test
    public void testFallBack() throws Exception {
        HystrixCommand<String> command = new CommandHelloFallBack();
        String result = command.execute();
        System.out.println(result);
    }
}
