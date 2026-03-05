package com.example.sell.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * @author 屈轩
 */
@Configuration
public class ThreadPoolConfig {

    // 获取 CPU 的核心数
    private static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();

    /**
     * 【CPU 密集型线程池】
     * 场景：复杂的算法计算、加解密、视频解码、大量数据转换
     * 配置策略：线程数少，避免过多的上下文切换
     */
    @Bean(name = "cpuTaskExecutor")
    public ThreadPoolTaskExecutor cpuTaskExecutor(){
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 核心线程数：CPU核数 + 1 (保证 CPU 充分利用，+1 是为了防止偶发的缺页中断导致 CPU 空闲)
        executor.setCorePoolSize(CPU_COUNT + 1);
        // 最大线程数：CPU 密集型不要设置太大，CPU核数 * 2 即可
        executor.setMaxPoolSize(CPU_COUNT * 2);
        // 队列容量：因为处理慢（计算量大），队列可以稍微大一点，缓冲等待的任务
        executor.setQueueCapacity(500);
        // 线程空闲存活时间
        executor.setKeepAliveSeconds(60);
        // 线程前缀
        executor.setThreadNamePrefix("cpu-exec-");
        // 拒绝策略：由调用者所在线程执行
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 优雅停机
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.initialize();
        return executor;
    }

    /**
     * 【IO 密集型线程池】 (通常 Web 应用用这个更多)
     * 场景：数据库读写、HTTP 接口调用、Redis 操作、文件读写
     * 配置策略：线程数多，因为线程大部分时间在等待 IO，CPU 是空闲的
     */
    @Bean(name = "ioTaskExecutor") // 也可以设为 @Primary，作为默认线程池
    public ThreadPoolTaskExecutor ioTaskExecutor(){
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 核心线程数：CPU核数 * 2 (这是一个保守的经验值)
        executor.setCorePoolSize(CPU_COUNT * 2);
        // 最大线程数：IO 密集型可以开大一点，应对突发流量，例如 CPU核数 * 4 或 * 5
        executor.setMaxPoolSize(CPU_COUNT * 5);
        // 队列容量：IO 任务处理通常较快，队列不宜过大，防止积压过多导致内存溢出
        executor.setQueueCapacity(200);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("io-exec-");
        // 拒绝策略：CallerRunsPolicy 是最稳妥的，防止丢数据
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.initialize();
        return executor;
    }

    /**
     * 【定时任务线程池】
     * 注意：Spring 的 setter 方法返回 void，不能链式调用 (.setX().setY() 是错误的)
     */
    @Bean(name = "schedulerTaskExecutor")
    public ThreadPoolTaskScheduler threadPoolTaskScheduler(){
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        // 线程池大小：根据你的定时任务数量设置，建议至少 5，防止任务阻塞
        scheduler.setPoolSize(5);
        scheduler.setThreadNamePrefix("scheduler-task-");
        // 等待任务完成再关闭
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(60);
        // 错误处理（可选）：打印异常日志
        scheduler.setErrorHandler(t -> System.err.println("定时任务执行异常: " + t.getMessage()));

        scheduler.initialize();
        return scheduler;
    }
}