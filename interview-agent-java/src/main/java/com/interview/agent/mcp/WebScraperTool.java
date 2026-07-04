package com.interview.agent.mcp;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 网页抓取 MCP 工具：用于抓取 JD 链接等网页内容。
 *
 * Java 版本使用 HttpClient 进行基础抓取，对于需要 JS 渲染的页面建议用户直接粘贴内容。
 *
 * @author 陈龙强
 */
@Slf4j
@Component
public class WebScraperTool {

    private final HttpClient httpClient;

    public WebScraperTool() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * 抓取网页文本内容
     */
    public String scrape(String url) {
        try {
            log.info("[WebScraper] 抓取网页: {}", url);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 (compatible; InterviewAgent/1.0)")
                    .timeout(Duration.ofSeconds(15))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("[WebScraper] 抓取失败，状态码: {}", response.statusCode());
                return null;
            }

            // 简单的 HTML 标签清理
            String body = response.body();
            body = body.replaceAll("<script[^>]*>[\\s\\S]*?</script>", "");
            body = body.replaceAll("<style[^>]*>[\\s\\S]*?</style>", "");
            body = body.replaceAll("<[^>]+>", " ");
            body = body.replaceAll("\\s+", " ").trim();

            log.info("[WebScraper] 抓取完成，内容长度: {}", body.length());
            return body;
        } catch (Exception e) {
            log.warn("[WebScraper] 抓取异常: {}", e.getMessage());
            return null;
        }
    }
}
