package command;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.netflix.hystrix.HystrixCommand;
import com.netflix.hystrix.HystrixCommandGroupKey;
import com.netflix.hystrix.HystrixCommandKey;
import com.netflix.hystrix.HystrixCommandProperties;
import com.netflix.hystrix.HystrixThreadPoolProperties;
import com.netflix.hystrix.collapser.RequestCollapserFactory.Setter;

//重载HystrixCommand 的getFallback方法实现逻辑
public class HelloWorldCommand3 extends HystrixCommand<String> {
	private final String name;

	public HelloWorldCommand3(String name) {
		super(
				Setter.withGroupKey(
						HystrixCommandGroupKey.Factory.asKey("HelloWorldGroup"))
						.andCommandKey(HystrixCommandKey.Factory.asKey("HelloWorldKey"))
						.andCommandPropertiesDefaults(HystrixCommandProperties.Setter()
						    		        //实行超时
						    		        .withExecutionIsolationThreadTimeoutInMilliseconds(
													5000)
                                            .withCircuitBreakerRequestVolumeThreshold(2)//10秒钟内至少2此请求失败，熔断器才发挥起作用
                                            .withCircuitBreakerSleepWindowInMilliseconds(3000)//熔断器中断请求30秒后会进入半打开状态,放部分流量过去重试
                                            .withCircuitBreakerErrorThresholdPercentage(50)//错误率达到50开启熔断保护
                                            .withExecutionTimeoutEnabled(false))//使用dubbo的超时，禁用这里的超时
              .andThreadPoolPropertiesDefaults(HystrixThreadPoolProperties.Setter().withCoreSize(3)));//线程池为30
		this.name = name;
	}

	@Override
	protected String getFallback() {
		return "exeucute Falled_"+System.currentTimeMillis();
	}

	@Override
	protected String run() throws Exception {
		// sleep 1 秒,调用会超时
		TimeUnit.MILLISECONDS.sleep(4000);
		System.out.println("run>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>");
		throw new RuntimeException();
		//return "Hello " + name + " thread:" + Thread.currentThread().getName();
	}

	public static void main(String[] args) throws Exception {
		
		ExecutorService executorService = Executors.newFixedThreadPool(1);
        for (int i = 0; i < 10; i++) {
            executorService.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                    	final HelloWorldCommand3 command = new HelloWorldCommand3("test-Fallback");
                    	String result = command.execute();
                    	System.out.println(result);
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                }
            });

        }
        
        for (int i = 0; i < 10; i++) {
            executorService.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                    	Thread.sleep(1000);
                    	final HelloWorldCommand3 command = new HelloWorldCommand3("test-Fallback");
                    	String result = command.execute();
                    	System.out.println(result);
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                }
            });

        }
        
		
	}
}
/*
 * 运行结果:getFallback() 调用运行 getFallback executed
 */