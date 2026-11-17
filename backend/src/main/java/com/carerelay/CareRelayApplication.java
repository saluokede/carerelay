package com.carerelay;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 医养接力站 · 面向出院老人的智能康复照护与社区随访
 *
 * <p>启动后自检入口：
 * <ul>
 *   <li>GET /api/v1/health        服务存活</li>
 *   <li>GET /api/v1/ai/ping       大模型连通性（D0 验证项，配置 Key 后即真实调用）</li>
 *   <li>GET /swagger-ui.html      接口文档</li>
 * </ul>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@MapperScan("com.carerelay.**.mapper")
@EnableScheduling
public class CareRelayApplication {

    public static void main(String[] args) {
        SpringApplication.run(CareRelayApplication.class, args);
    }
}
