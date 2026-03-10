package com.example.sell.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * 定时任务线程池配置
 * 解决Spring Boot默认单线程定时任务阻塞问题
 *
 * @author 屈轩
 */
@Configuration
public class SchedulingConfig implements SchedulingConfigurer {

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        // 配置线程池大小，根据定时任务数量调整，建议8-20个
        scheduler.setPoolSize(10);
        scheduler.setThreadNamePrefix("scheduled-task-");
        // 等待任务完成后再关闭
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(60);
        scheduler.initialize();
        taskRegistrar.setTaskScheduler(scheduler);
    }
}