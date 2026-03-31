package com.example.javaparser;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Java解析服务应用主类
 * 使用JaMoPP替代MoDisco进行Java源码解析
 */
@SpringBootApplication
public class JavaParserApplication {

    public static void main(String[] args) {
        SpringApplication.run(JavaParserApplication.class, args);
    }
}
