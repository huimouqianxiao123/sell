package com.example.sell.skills;

import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import com.alibaba.cloud.ai.graph.skills.registry.classpath.ClasspathSkillRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @author 屈轩
 */
@Configuration
public class skillConfig {



    @Bean("skillsAgentHook")
    public SkillsAgentHook skillsAgentHook(){
        SkillRegistry registry = ClasspathSkillRegistry.builder()
                .classpathPath("skill")
                .build();

        SkillsAgentHook hook = SkillsAgentHook.builder()
                .skillRegistry(registry)
                .build();
        return hook;
    }


}
