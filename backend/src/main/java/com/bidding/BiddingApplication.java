package com.bidding;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 启动类。
 * @MapperScan 告诉 MyBatis-Plus 去哪里扫描 Mapper 接口并生成代理实现。
 */
@SpringBootApplication
@MapperScan("com.bidding.mapper")
public class BiddingApplication {

    public static void main(String[] args) {
        SpringApplication.run(BiddingApplication.class, args);
    }
}
