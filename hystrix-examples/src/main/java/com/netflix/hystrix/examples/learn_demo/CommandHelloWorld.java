package com.netflix.hystrix.examples.learn_demo;

import com.netflix.hystrix.HystrixCommand;
import com.netflix.hystrix.HystrixCommandGroupKey;
import com.netflix.hystrix.HystrixCommandKey;
import com.netflix.hystrix.HystrixCommandProperties;
import org.junit.Test;
import rx.Observable;

import java.util.concurrent.Future;

/**
 * @Descriprion:
 * @Author:wuxiaoguang@58.com
 * @Date：created in 2019/11/28
 */
public class CommandHelloWorld extends HystrixCommand<String> {
    private String name;

    public void setName(String name) {
        this.name = name;
    }

    public CommandHelloWorld() {
        //super(HystrixCommandGroupKey.Factory.asKey("ExampleGroup"));
        super(Setter.withGroupKey(HystrixCommandGroupKey.Factory.asKey("ExampleGroup"))
                .andCommandKey(HystrixCommandKey.Factory.asKey("HelloWorld"))
                .andCommandPropertiesDefaults(HystrixCommandProperties.Setter()
                .withExecutionTimeoutInMilliseconds(1000*60*5)));
        this.name = "are you ok?";
    }

  /*  public CommandHelloWorld(String name) {
        super(HystrixCommandGroupKey.Factory.asKey("ExampleGroup"));
        this.name = name;
    }*/

    @Override
    protected String run() throws Exception {
        System.out.println("Thread Name:"+Thread.currentThread().getName());
        return "Hello " + name + "!";
    }

    @Test
    public void testSynchronous() throws Exception {
        System.out.println("Thread Name:"+Thread.currentThread().getName());
        HystrixCommand<String> command = new CommandHelloWorld();
        String result = command.execute();
        System.out.println(result);
    }

    @Test
    public void testAsynchronous() throws Exception {
        System.out.println("Thread Name:"+Thread.currentThread().getName());
        HystrixCommand<String> command = new CommandHelloWorld();
        Future<String> future = command.queue();
        String result = future.get();
        System.out.println(result);
    }

    @Test
    public void testObservable() throws Exception {
        Observable<String> observable = new CommandHelloWorld().observe();
        //同步阻塞执行
        String blockResult = observable.toBlocking().single();
        System.out.println(blockResult);

        //非阻塞执行
//        observable.subscribe(new Observer<String>() {
//            @Override
//            public void onCompleted() {
//
//            }
//
//            @Override
//            public void onError(Throwable throwable) {
//
//            }
//
//            @Override
//            public void onNext(String s) {
//                System.out.println("Thread Name:"+Thread.currentThread().getName());
//                System.out.println("onNext: " + s);
//            }
//        });
//
//
//        Thread.sleep(1000*60);
    }
}
