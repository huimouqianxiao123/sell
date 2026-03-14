package com.example.sell.utils;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;

public class main {
    public static void main(String[] args) {
        Yaml yaml = new Yaml();
        // 使用类路径加载 resources 目录下的文件
        InputStream inputStream = main.class.getClassLoader().getResourceAsStream("application.yml");
        
        if (inputStream == null) {
            System.err.println("无法找到 application.yml 文件");
            return;
        }
        
        Object obj = yaml.load(inputStream);
        System.out.println(obj);
    }
}
