package com.interview.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * @author 陈龙强
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "app")
public class AppConfig {

    private MilvusProperties milvus = new MilvusProperties();
    private JwtProperties jwt = new JwtProperties();
    private GitHubProperties github = new GitHubProperties();
    private AuthProperties auth = new AuthProperties();

    @Data
    public static class MilvusProperties {
        private String host = "localhost";
        private int port = 19530;
    }

    @Data
    public static class JwtProperties {
        private String secret = "interview-agent-default-secret";
        private long expiration = 86400000; // 24 hours
    }

    @Data
    public static class GitHubProperties {
        private String token = "";
    }

    @Data
    public static class AuthProperties {
        private boolean enabled = true;
    }
}
