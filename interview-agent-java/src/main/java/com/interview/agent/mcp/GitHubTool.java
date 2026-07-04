package com.interview.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.agent.config.AppConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * GitHub MCP 工具：搜索 GitHub 仓库，用于复习计划推荐开源项目。
 *
 * @author 陈龙强
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.github", name = "token", matchIfMissing = false)
public class GitHubTool {

    private final String token;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public GitHubTool(AppConfig appConfig) {
        this.token = appConfig.getGithub().getToken();
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 搜索 GitHub 仓库
     * @param query 搜索关键词（如 "redis tutorial stars:>100"）
     * @param maxResults 最大结果数
     * @return 格式化的搜索结果（Markdown 格式）
     */
    public String searchRepositories(String query, int maxResults) {
        try {
            String url = String.format(
                    "https://api.github.com/search/repositories?q=%s&sort=stars&order=desc&per_page=%d",
                    java.net.URLEncoder.encode(query, "UTF-8"), maxResults);

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "application/vnd.github.v3+json");

            if (token != null && !token.isEmpty()) {
                requestBuilder.header("Authorization", "token " + token);
            }

            HttpResponse<String> response = httpClient.send(requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("[GitHub] 搜索失败，状态码: {}", response.statusCode());
                return null;
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode items = root.get("items");
            if (items == null || !items.isArray() || items.isEmpty()) {
                return null;
            }

            StringBuilder sb = new StringBuilder();
            for (JsonNode item : items) {
                String name = item.get("full_name").asText();
                int stars = item.get("stargazers_count").asInt();
                String htmlUrl = item.get("html_url").asText();
                String desc = item.has("description") && !item.get("description").isNull()
                        ? item.get("description").asText() : "无描述";
                sb.append(String.format("- **%s**（⭐ %d）：%s\n  链接：%s\n", name, stars, desc, htmlUrl));
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("[GitHub] 搜索异常: {}", e.getMessage());
            return null;
        }
    }

    /** 暴露为 Spring AI ToolCallback，供 ReactAgent 在生成复习计划时按需自主调用 */
    public ToolCallback asToolCallback() {
        return FunctionToolCallback
                .builder("search_github_repos", (GithubSearchRequest req) -> {
                    String q = (req == null || req.query() == null) ? "" : req.query();
                    String result = searchRepositories(q + " stars:>100", 5);
                    return (result == null || result.isEmpty()) ? "未找到相关开源项目。" : result;
                })
                .description("根据技术关键词搜索 GitHub 上 star 数较多的开源项目与教程，返回项目清单"
                        + "（名称、star 数、链接、简介）。为候选人推荐真实可用的学习项目时调用，关键词用英文技术词。")
                .inputType(GithubSearchRequest.class)
                .build();
    }

    /** ReactAgent 调用本工具时的入参 */
    public record GithubSearchRequest(String query) {}
}
