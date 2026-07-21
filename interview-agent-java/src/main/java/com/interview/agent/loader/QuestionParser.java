package com.interview.agent.loader;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 题库解析器：使用 LLM 将非结构化题库文本解析为结构化题目
 * - 超过 maxSegmentLen 的文本按段落边界分段，逐段调用 LLM
 * - 校验题目字段合法性
 *
 * @author 陈龙强
 */
@Slf4j
@Component
public class QuestionParser {

    // 单段越大，模型需要一次生成的 JSON 越长。默认控制在 4000 字，并允许通过环境变量调整。
    private static final int MAX_SEGMENT_LEN = Integer.parseInt(System.getenv().getOrDefault("QUESTION_SEGMENT_LEN", "4000"));
    /**
     * 题库解析最大并发数，限制在 1~8，避免大量分段同时请求模型触发限流。
     */
    private static final int PARSE_CONCURRENCY = Math.clamp(
            Integer.parseInt(System.getenv().getOrDefault("QUESTION_PARSE_CONCURRENCY", "3")), 1, 8);
    /**
     * 单段模型调用超时时间，默认 300 秒，可通过环境变量覆盖。
     */
    private static final long SEGMENT_TIMEOUT_SECONDS = Long.parseLong(
            System.getenv().getOrDefault("QUESTION_PARSE_TIMEOUT", "300"));
    /**
     * 本地识别成功后，每次仅把指定数量的题目标题交给模型补充元数据。
     */
    private static final int METADATA_BATCH_SIZE = Math.clamp(
            Integer.parseInt(System.getenv().getOrDefault("QUESTION_METADATA_BATCH_SIZE", "30")), 1, 100);
    /**
     * 解析线程编号，用于在日志中区分并行执行的分段任务。
     */
    private static final AtomicInteger PARSER_THREAD_NUMBER = new AtomicInteger();
    /**
     * 标题最小长度
     */
    private static final int MIN_QUESTION_CONTENT_LENGTH = 2;
    /**
     * 题目难度
     */
    private static final Set<String> VALID_DIFFICULTIES = Set.of("easy", "medium", "hard");
    /**
     * 题目类型
     */
    private static final Set<String> VALID_TYPES = Set.of("basic", "project", "design", "algorithm");

    /**
     * Markdown 二、三级标题可以直接作为题目边界。
     */
    private static final Pattern MARKDOWN_QUESTION_PATTERN = Pattern.compile("^#{2,3}\\s+\\S.*$");

    /**
     * 提取 PDF 常见数字题号之后的标题文本，用于进一步判断它是否确实是一道问题。
     */
    private static final Pattern NUMBERED_QUESTION_PATTERN = Pattern.compile(
            "^(?:第\\s*\\d{1,4}\\s*题\\s*[:：.．、]?|[（(]?\\d{1,4}\\s*[.．、:：)）])\\s*(\\S.*)$");

    /**
     * 提取 Markdown 或普通数字题号中的序号，用于校验本地识别结果是否连续可靠。
     */
    private static final Pattern QUESTION_NUMBER_PATTERN = Pattern.compile(
            "^(?:#{2,3}\\s*)?(?:第\\s*)?[（(]?(\\d{1,4})(?:\\s*题)?\\s*[.．、:：)）]?");

    /**
     * 答案列表常见陈述式开头，用于避免把“1.不能……、2.可以……”识别为知识点标题。
     */
    private static final Pattern ANSWER_ITEM_PREFIX_PATTERN = Pattern.compile(
            "^(?:不能|可以|需要|包括|用于|是指|是|将|通过|使用|支持|负责|提供|采用|根据)");

    /**
     * 数字编号只有包含明确问题语义时才作为边界，避免把答案中的“1.……、2.……”误判为新题。
     */
    private static final Pattern QUESTION_TEXT_PATTERN = Pattern.compile(
            "[?？]|什么|为何|为什么|如何|怎么|怎样|哪些|哪个|哪种|是否|能否|区别|异同|有什么|"
                    + "请(?:说明|解释|介绍|分析|比较|设计|实现)|谈谈|作用\\s*$|原理\\s*$|流程\\s*$|"
                    + "场景\\s*$|优缺点\\s*$|生命周期\\s*$|机制\\s*$|类型\\s*$|步骤\\s*$|[：:]\\s*$");

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final String QUESTION_PARSER_PROMPT = """
            你是一个面试题库解析专家。请从以下文本中提取所有面试题，并转换为结构化 JSON 格式。
            
            ## 输入文本
            %s
            
            ## 输出要求
            
            请输出一个 JSON 数组，每道题包含以下字段：
            
            [
              {
                "content": "题目文本（完整的面试问题）",
                "reference": "参考答案或答案要点",
                "difficulty": "easy 或 medium 或 hard",
                "type": "basic 或 project 或 design 或 algorithm",
                "skills": ["技能标签1", "技能标签2"]
              }
            ]
            
            ## 严格规则（必须遵守）
            
            1. difficulty 只能是以下三个值之一：easy、medium、hard。根据题目考察深度判断。
            2. type 只能是以下四个值之一：
               - basic：基础知识/概念题
               - project：项目经验/实战题
               - design：系统设计/架构题
               - algorithm：算法/数据结构题
            3. skills 必须是非空数组，填写该题考察的技术方向。
            4. content 必须是完整的题目文本，不要截断。
            5. reference 必须完整照搬原文中的答案内容，不得改写、缩写或重新组织。如果原文没有答案，填空字符串 ""。
            6. 只输出 JSON 数组，不要输出任何其他文字。
            
            请严格按照上述格式输出，不要添加额外字段，不要修改字段名称。""";

    /**
     * 本地已提取题目和答案后，只让模型补充体量较小的分类与技能元数据。
     */
    private static final String QUESTION_METADATA_PROMPT = """
            你是面试题分类专家。下面 JSON 中的题目正文已经由程序可靠提取，请勿改写题目。
            
            ## 输入
            %s
            
            ## 输出
            只输出 JSON 数组，每项必须保留原 index，并补充 difficulty、type、skills：
            [
              {
                "index": 0,
                "difficulty": "easy 或 medium 或 hard",
                "type": "basic 或 project 或 design 或 algorithm",
                "skills": ["技能标签1", "技能标签2"]
              }
            ]
            
            不要输出 content、reference 或任何解释文字。""";

    private final ChatModel chatModel;

    /**
     * 题库解析专用有界线程池。固定并发数保护模型服务，队列满时由上传线程执行以形成背压。
     */
    private final ExecutorService parseExecutor = new ThreadPoolExecutor(
            PARSE_CONCURRENCY,
            PARSE_CONCURRENCY,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(32),
            runnable -> {
                Thread thread = new Thread(runnable,
                        "question-parser-" + PARSER_THREAD_NUMBER.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.CallerRunsPolicy());

    public QuestionParser(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * 应用关闭时停止题库解析线程池，不再接收新的模型解析任务。
     */
    @PreDestroy
    private void shutdownParseExecutor() {
        parseExecutor.shutdown();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ParsedQuestion {
        private String id;
        private String content;
        private String reference;
        private String difficulty;
        private String type;
        private List<String> skills;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ParseError {
        private int index;
        private String reason;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ParseResult {
        private int total;
        private int success;
        private int failed;
        private List<ParseError> errors;
        private List<ParsedQuestion> questions;
    }

    /**
     * 模型为本地题目补充的轻量元数据，通过 index 与原题一一对应。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    private static class QuestionMetadata {
        private Integer index;
        private String difficulty;
        private String type;
        private List<String> skills;
    }

    /**
     * 接收并行解析进度，参数依次为已完成分段数、总分段数和累计识别题目数。
     */
    @FunctionalInterface
    public interface ParseProgressListener {
        void onProgress(int completedSegments, int totalSegments, int parsedQuestions);
    }

    /**
     * 解析非结构化题库文本为结构化题目，不订阅中间进度。
     */
    public ParseResult parseQuestionBank(String rawText) {
        return parseQuestionBank(rawText, null);
    }

    /**
     * 解析非结构化题库文本为结构化题目，并在每个分段完成后通知调用方当前累计进度。
     */
    public ParseResult parseQuestionBank(String rawText, ParseProgressListener progressListener) {
        List<ParsedQuestion> allRaw;

        List<ParsedQuestion> locallyParsed = extractQuestionsLocally(rawText);
        if (!locallyParsed.isEmpty()) {
            log.info("[question_parser] 本地结构化解析成功，共识别 {} 道连续编号题，仅调用模型补充元数据",
                    locallyParsed.size());
            allRaw = enrichQuestionMetadata(locallyParsed, progressListener);
        } else if (rawText.length() <= MAX_SEGMENT_LEN) {
            log.info("[question_parser] 文档结构不满足本地解析条件，回退完整 LLM 解析");
            allRaw = parseSegment(rawText);
            notifyProgress(progressListener, 1, 1, allRaw.size());
        } else {
            List<String> segments = splitByQuestion(rawText, MAX_SEGMENT_LEN);
            log.info("[question_parser] 文档结构不满足本地解析条件；文本共 {} 字符，拆分为 {} 段，按最大并发 {} 完整解析",
                    rawText.length(), segments.size(), PARSE_CONCURRENCY);
            allRaw = parseSegmentsInParallel(segments, progressListener);
        }

        List<ParsedQuestion> allParsed = new ArrayList<>();
        List<ParseError> allErrors = new ArrayList<>();

        long timestamp = Instant.now().getEpochSecond();
        for (int i = 0; i < allRaw.size(); i++) {
            int idx = i + 1;
            ParsedQuestion q = allRaw.get(i);
            String err = validateQuestion(q);
            if (err != null) {
                allErrors.add(ParseError.builder().index(idx).reason(err).build());
                continue;
            }
            q.setId(String.format("user_%d_%d", timestamp, idx));
            allParsed.add(q);
        }

        return ParseResult.builder()
                .total(allRaw.size())
                .success(allParsed.size())
                .failed(allErrors.size())
                .errors(allErrors)
                .questions(allParsed)
                .build();
    }

    /**
     * 在题号和答案边界足够明确时直接从原文提取题目。任何编号缺失、跳号或答案为空的情况
     * 都返回空列表，让调用方回退到完整 LLM 解析，避免静默丢题或截断答案。
     */
    static List<ParsedQuestion> extractQuestionsLocally(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return Collections.emptyList();
        }

        String[] lines = rawText.split("\\R", -1);
        List<Integer> boundaries = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            if (isQuestionStart(lines, i)) {
                boundaries.add(i);
            }
        }
        if (boundaries.size() < 2) {
            return Collections.emptyList();
        }

        // 首个已识别边界前若还有数字题号，说明边界识别不完整，不能安全采用本地结果。
        for (int i = 0; i < boundaries.getFirst(); i++) {
            if (NUMBERED_QUESTION_PATTERN.matcher(lines[i].trim()).matches()) {
                return Collections.emptyList();
            }
        }

        List<Integer> questionNumbers = new ArrayList<>(boundaries.size());
        for (int boundary : boundaries) {
            Matcher numberMatcher = QUESTION_NUMBER_PATTERN.matcher(lines[boundary].trim());
            if (!numberMatcher.find()) {
                return Collections.emptyList();
            }
            questionNumbers.add(Integer.parseInt(numberMatcher.group(1)));
        }
        for (int i = 1; i < questionNumbers.size(); i++) {
            if (questionNumbers.get(i) != questionNumbers.get(i - 1) + 1) {
                return Collections.emptyList();
            }
        }

        List<ParsedQuestion> questions = new ArrayList<>(boundaries.size());
        for (int i = 0; i < boundaries.size(); i++) {
            int start = boundaries.get(i);
            int end = i + 1 < boundaries.size() ? boundaries.get(i + 1) : lines.length;
            String titleLine = lines[start].trim();
            Matcher numberMatcher = QUESTION_NUMBER_PATTERN.matcher(titleLine);
            if (!numberMatcher.find()) {
                return Collections.emptyList();
            }

            String content = titleLine.substring(numberMatcher.end()).trim();
            String reference = String.join("\n", Arrays.copyOfRange(lines, start + 1, end)).trim();
            if (content.length() < MIN_QUESTION_CONTENT_LENGTH || reference.isEmpty()) {
                return Collections.emptyList();
            }
            questions.add(ParsedQuestion.builder()
                    .content(content)
                    .reference(reference)
                    .build());
        }
        return questions;
    }

    /**
     * 将本地提取的题目标题按批次并行交给模型分类，再按原始索引合并。单批失败时使用默认
     * 元数据，确保已经可靠提取的题目正文和参考答案不会丢失。
     */
    private List<ParsedQuestion> enrichQuestionMetadata(List<ParsedQuestion> questions,
                                                        ParseProgressListener progressListener) {
        if (questions == null || questions.isEmpty()) {
            return Collections.emptyList();
        }

        int batchCount = (questions.size() + METADATA_BATCH_SIZE - 1) / METADATA_BATCH_SIZE;
        List<CompletableFuture<List<QuestionMetadata>>> futures = new ArrayList<>(batchCount);
        AtomicInteger completedBatches = new AtomicInteger();
        AtomicInteger processedQuestions = new AtomicInteger();
        Object progressLock = new Object();

        for (int batchIndex = 0; batchIndex < batchCount; batchIndex++) {
            int fromIndex = batchIndex * METADATA_BATCH_SIZE;
            int toIndex = Math.min(fromIndex + METADATA_BATCH_SIZE, questions.size());
            int currentBatch = batchIndex;
            CompletableFuture<List<QuestionMetadata>> future = CompletableFuture
                    .supplyAsync(() -> parseMetadataBatch(questions, fromIndex, toIndex), parseExecutor)
                    .orTimeout(SEGMENT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .exceptionally(error -> {
                        Throwable cause = unwrapCompletionError(error);
                        log.warn("[question_parser] 第 {}/{} 个元数据批次解析失败或超时，将使用默认分类: {}",
                                currentBatch + 1, batchCount, cause.getMessage());
                        return Collections.emptyList();
                    })
                    .thenApply(metadata -> {
                        synchronized (progressLock) {
                            int completed = completedBatches.incrementAndGet();
                            int processed = processedQuestions.addAndGet(toIndex - fromIndex);
                            notifyProgress(progressListener, completed, batchCount, processed);
                        }
                        return metadata;
                    });
            futures.add(future);
        }

        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        Map<Integer, QuestionMetadata> metadataByIndex = new HashMap<>();
        futures.forEach(future -> future.join().stream()
                .filter(Objects::nonNull)
                .filter(metadata -> metadata.getIndex() != null
                        && metadata.getIndex() >= 0
                        && metadata.getIndex() < questions.size())
                .forEach(metadata -> metadataByIndex.putIfAbsent(metadata.getIndex(), metadata)));

        List<ParsedQuestion> enriched = new ArrayList<>(questions.size());
        for (int i = 0; i < questions.size(); i++) {
            ParsedQuestion original = questions.get(i);
            QuestionMetadata metadata = metadataByIndex.get(i);
            enriched.add(ParsedQuestion.builder()
                    .content(original.getContent())
                    .reference(original.getReference())
                    .difficulty(normalizeDifficulty(metadata == null ? null : metadata.getDifficulty()))
                    .type(normalizeType(metadata == null ? null : metadata.getType()))
                    .skills(normalizeSkills(metadata == null ? null : metadata.getSkills(), original.getContent()))
                    .build());
        }
        return enriched;
    }

    /**
     * 仅发送指定批次的题目索引和标题给模型，并解析其返回的分类元数据。
     */
    private List<QuestionMetadata> parseMetadataBatch(List<ParsedQuestion> questions,
                                                      int fromIndex,
                                                      int toIndex) {
        List<Map<String, Object>> input = new ArrayList<>(toIndex - fromIndex);
        for (int i = fromIndex; i < toIndex; i++) {
            input.add(Map.of("index", i, "content", questions.get(i).getContent()));
        }

        try {
            String prompt = String.format(QUESTION_METADATA_PROMPT, objectMapper.writeValueAsString(input));
            ChatResponse response = chatModel.call(new Prompt(prompt));
            String content = extractJSONArray(Objects.requireNonNull(response.getResult().getOutput().getText()));
            return objectMapper.readValue(content, new TypeReference<>() {
            });
        } catch (Exception e) {
            throw new RuntimeException("question metadata parse failed: " + e.getMessage(), e);
        }
    }

    /**
     * 将模型难度规范化为合法枚举，缺失或非法时采用中等难度。
     */
    private static String normalizeDifficulty(String difficulty) {
        String normalized = difficulty == null ? "" : difficulty.trim().toLowerCase(Locale.ROOT);
        return VALID_DIFFICULTIES.contains(normalized) ? normalized : "medium";
    }

    /**
     * 将模型题型规范化为合法枚举，缺失或非法时采用基础题。
     */
    private static String normalizeType(String type) {
        String normalized = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        return VALID_TYPES.contains(normalized) ? normalized : "basic";
    }

    /**
     * 清理模型技能标签；没有有效标签时从题目标题生成一个简短标签作为稳定兜底。
     */
    private static List<String> normalizeSkills(List<String> skills, String content) {
        if (skills != null) {
            List<String> normalized = skills.stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(skill -> !skill.isEmpty())
                    .distinct()
                    .toList();
            if (!normalized.isEmpty()) {
                return normalized;
            }
        }

        String fallback = content == null ? "" : content.trim()
                .replaceFirst("[?？:：。！!]+$", "")
                .trim();
        if (fallback.length() > 20) {
            fallback = fallback.substring(0, 20);
        }
        return List.of(fallback.isEmpty() ? "综合知识" : fallback);
    }

    /**
     * 使用专用有界线程池并行解析所有分段，等待全部完成后按原分段顺序合并结果。
     * 每个 Future 自行处理超时和异常，因此单段失败不会影响其他分段。
     */
    private List<ParsedQuestion> parseSegmentsInParallel(List<String> segments,
                                                         ParseProgressListener progressListener) {
        long totalStart = System.nanoTime();
        List<CompletableFuture<List<ParsedQuestion>>> futures = new ArrayList<>(segments.size());
        AtomicInteger completedSegments = new AtomicInteger();
        AtomicInteger parsedQuestionCount = new AtomicInteger();
        Object progressLock = new Object();

        for (int i = 0; i < segments.size(); i++) {
            int segmentIndex = i;
            String segment = segments.get(i);
            CompletableFuture<List<ParsedQuestion>> future = CompletableFuture
                    .supplyAsync(() -> parseSegmentWithMetrics(segment, segmentIndex, segments.size()), parseExecutor)
                    .orTimeout(SEGMENT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .exceptionally(error -> {
                        Throwable cause = unwrapCompletionError(error);
                        log.warn("[question_parser] 第 {}/{} 段解析失败或超时，跳过: {}",
                                segmentIndex + 1, segments.size(), cause.getMessage());
                        return Collections.emptyList();
                    })
                    .thenApply(parsed -> {
                        synchronized (progressLock) {
                            int completed = completedSegments.incrementAndGet();
                            int parsedCount = parsedQuestionCount.addAndGet(parsed.size());
                            notifyProgress(progressListener, completed, segments.size(), parsedCount);
                        }
                        return parsed;
                    });
            futures.add(future);
        }

        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        List<ParsedQuestion> parsedQuestions = new ArrayList<>();
        futures.forEach(future -> parsedQuestions.addAll(future.join()));
        log.info("[question_parser] {} 段并行解析完成，共解析 {} 道题，总耗时 {} ms",
                segments.size(), parsedQuestions.size(), elapsedMillis(totalStart));
        return parsedQuestions;
    }

    /**
     * 安全调用进度监听器；监听器异常只记录日志，不影响题库解析主流程。
     */
    private static void notifyProgress(ParseProgressListener listener,
                                       int completedSegments,
                                       int totalSegments,
                                       int parsedQuestions) {
        if (listener == null) {
            return;
        }
        try {
            listener.onProgress(completedSegments, totalSegments, parsedQuestions);
        } catch (Exception e) {
            log.warn("[question_parser] 推送解析进度失败: {}", e.getMessage());
        }
    }

    /**
     * 调用模型解析单个分段，并记录该分段的字符数、题目数和耗时。
     */
    private List<ParsedQuestion> parseSegmentWithMetrics(String segment, int segmentIndex, int segmentCount) {
        long start = System.nanoTime();
        log.info("[question_parser] 开始解析第 {}/{} 段（{} 字符）",
                segmentIndex + 1, segmentCount, segment.length());
        List<ParsedQuestion> parsed = parseSegment(segment);
        log.info("[question_parser] 第 {}/{} 段解析出 {} 道题，耗时 {} ms",
                segmentIndex + 1, segmentCount, parsed.size(), elapsedMillis(start));
        return parsed;
    }

    /**
     * 获取 CompletableFuture 包装异常的最底层业务原因，便于日志输出。
     */
    private static Throwable unwrapCompletionError(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    /**
     * 将指定开始时间到当前时间的纳秒差转换为毫秒。
     */
    private static long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    /**
     * 对单段文本调用 LLM 解析
     */
    private List<ParsedQuestion> parseSegment(String text) {
        String prompt = String.format(QUESTION_PARSER_PROMPT, text);
        ChatResponse response;
        try {
            response = chatModel.call(new Prompt(prompt));
        } catch (Exception e) {
            Throwable root = e;
            while (root.getCause() != null) root = root.getCause();
            log.error("[QuestionParser] DashScope 调用失败 — root cause: {}", root.getMessage(), e);
            throw e;
        }

        String content = extractJSONArray(Objects.requireNonNull(response.getResult().getOutput().getText()));

        try {
            return objectMapper.readValue(content, new TypeReference<>() {
            });
        } catch (Exception e) {
            // 尝试修复被截断的 JSON
            String repaired = tryRepairJSONArray(content);
            if (repaired != null) {
                try {
                    List<ParsedQuestion> result = objectMapper.readValue(repaired, new TypeReference<>() {
                    });
                    log.info("[question_parser] JSON 被截断，自动修复成功（恢复 {} 道题）", result.size());
                    return result;
                } catch (Exception ignored) {
                }
            }
            throw new RuntimeException("question_parser: json parse failed: " + e.getMessage());
        }
    }

    /**
     * 尝试修复被截断的 JSON 数组
     */
    private String tryRepairJSONArray(String content) {
        for (int i = content.length() - 1; i >= 0; i--) {
            if (content.charAt(i) == '}') {
                String candidate = content.substring(0, i + 1).replaceAll("[,\\s]+$", "") + "]";
                int start = candidate.indexOf('[');
                if (start >= 0) {
                    return candidate.substring(start);
                }
                break;
            }
        }
        return null;
    }

    /**
     * 校验单道题的字段合法性，并返回可直接展示给用户的中文失败原因。
     */
    static String validateQuestion(ParsedQuestion q) {
        if (q == null) {
            return "题目数据为空";
        }
        if (q.getContent() != null) q.setContent(q.getContent().trim());
        if (q.getReference() != null) q.setReference(q.getReference().trim());
        if (q.getDifficulty() != null) q.setDifficulty(q.getDifficulty().trim().toLowerCase());
        if (q.getType() != null) q.setType(q.getType().trim().toLowerCase());

        if (q.getContent() == null || q.getContent().length() < MIN_QUESTION_CONTENT_LENGTH) {
            return String.format("题目内容过短（当前 %d 个字符，至少需要 %d 个字符）",
                    q.getContent() == null ? 0 : q.getContent().length(), MIN_QUESTION_CONTENT_LENGTH);
        }
        if (q.getReference() == null || q.getReference().isEmpty()) {
            return "参考答案为空";
        }
        if (!VALID_DIFFICULTIES.contains(q.getDifficulty())) {
            return String.format("难度分类无效（当前值：%s）", Objects.toString(q.getDifficulty(), "未填写"));
        }
        if (!VALID_TYPES.contains(q.getType())) {
            return String.format("题型分类无效（当前值：%s）", Objects.toString(q.getType(), "未填写"));
        }
        if (q.getSkills() == null || q.getSkills().isEmpty()) {
            return "技能标签为空";
        }
        List<String> cleaned = q.getSkills().stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
        if (cleaned.isEmpty()) {
            return "技能标签为空";
        }
        q.setSkills(cleaned);
        return null;
    }

    /**
     * 从 LLM 输出中提取 JSON 数组
     */
    static String extractJSONArray(String text) {
        String trimmed = text.trim();

        // 直接以 [ 开头
        if (trimmed.startsWith("[")) {
            int end = trimmed.lastIndexOf(']');
            if (end > 0) {
                return trimmed.substring(0, end + 1);
            }
            return trimmed;
        }

        // markdown 代码块
        int idx = text.indexOf("```json");
        if (idx >= 0) {
            int start = idx + 7;
            int end = text.indexOf("```", start);
            if (end >= 0) {
                return text.substring(start, end).trim();
            }
        }
        idx = text.indexOf("```");
        if (idx >= 0) {
            int start = idx + 3;
            int nl = text.indexOf('\n', start);
            if (nl >= 0) start = nl + 1;
            int end = text.indexOf("```", start);
            if (end >= 0) {
                return text.substring(start, end).trim();
            }
        }

        // 兜底：查找 JSON 数组起止
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    /**
     * 按题目标题边界分段
     */
    static List<String> splitByQuestion(String text, int maxLen) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }
        if (maxLen <= 0) {
            throw new IllegalArgumentException("maxLen must be positive");
        }

        String[] lines = text.split("\\R", -1);

        List<Integer> splitPoints = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            if (isQuestionStart(lines, i)) {
                splitPoints.add(i);
            }
        }

        if (splitPoints.isEmpty()) {
            return splitByParagraph(text, maxLen);
        }

        // 按标题或题号切割成独立题目块，同时保留第一个题目前的说明文字。
        List<String> blocks = new ArrayList<>();
        if (splitPoints.getFirst() > 0) {
            String preamble = String.join("\n", Arrays.copyOfRange(lines, 0, splitPoints.getFirst())).trim();
            if (!preamble.isEmpty()) {
                blocks.add(preamble);
            }
        }
        for (int i = 0; i < splitPoints.size(); i++) {
            int start = splitPoints.get(i);
            int end = (i + 1 < splitPoints.size()) ? splitPoints.get(i + 1) : lines.length;
            String block = String.join("\n", Arrays.copyOfRange(lines, start, end)).trim();
            if (!block.isEmpty()) {
                blocks.add(block);
            }
        }

        // 合并为不超过 maxLen 的段；单个题目块过长时也必须硬切，不能绕过上限。
        List<String> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String b : blocks) {
            List<String> blockSegments = splitTextByLimit(b, maxLen);
            for (String blockSegment : blockSegments) {
                if (!current.isEmpty() && current.length() + blockSegment.length() + 2 > maxLen) {
                    segments.add(current.toString());
                    current = new StringBuilder();
                }
                if (!current.isEmpty()) current.append("\n\n");
                current.append(blockSegment);
            }
        }
        if (!current.isEmpty()) {
            segments.add(current.toString());
        }
        if (segments.isEmpty()) {
            segments.add(text);
        }
        return segments;
    }

    /**
     * 判断一行是否为题目起始位置：Markdown 标题直接通过，数字题号还必须匹配问题语义。
     */
    static boolean isQuestionStart(String line) {
        if (line == null || line.isBlank()) {
            return false;
        }
        String trimmed = line.trim();
        if (MARKDOWN_QUESTION_PATTERN.matcher(trimmed).matches()) {
            return true;
        }
        Matcher numberedQuestion = NUMBERED_QUESTION_PATTERN.matcher(trimmed);
        return numberedQuestion.matches()
                && QUESTION_TEXT_PATTERN.matcher(numberedQuestion.group(1).trim()).find();
    }

    /**
     * 结合后续正文判断无问句特征的数字短标题。标题后必须存在解释段，连续数字列表和答案式陈述不通过。
     */
    static boolean isQuestionStart(String[] lines, int lineIndex) {
        if (lines == null || lineIndex < 0 || lineIndex >= lines.length) {
            return false;
        }
        if (isQuestionStart(lines[lineIndex])) {
            return true;
        }

        Matcher numberedQuestion = NUMBERED_QUESTION_PATTERN.matcher(lines[lineIndex].trim());
        if (!numberedQuestion.matches()) {
            return false;
        }
        String title = numberedQuestion.group(1).trim();
        if (title.length() > 60 || ANSWER_ITEM_PREFIX_PATTERN.matcher(title).find()) {
            return false;
        }

        for (int i = lineIndex + 1; i < lines.length; i++) {
            String nextLine = lines[i].trim();
            if (nextLine.isEmpty()) {
                continue;
            }
            return !MARKDOWN_QUESTION_PATTERN.matcher(nextLine).matches()
                    && !NUMBERED_QUESTION_PATTERN.matcher(nextLine).matches();
        }
        return false;
    }

    /**
     * 按段落边界分段（回退方案）
     */
    static List<String> splitByParagraph(String text, int maxLen) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }
        if (maxLen <= 0) {
            throw new IllegalArgumentException("maxLen must be positive");
        }

        String[] paragraphs = text.split("(?:\\R\\s*){2,}");
        List<String> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String p : paragraphs) {
            p = p.trim();
            if (p.isEmpty()) continue;

            for (String part : splitTextByLimit(p, maxLen)) {
                if (!current.isEmpty() && current.length() + part.length() + 2 > maxLen) {
                    segments.add(current.toString());
                    current = new StringBuilder();
                }
                if (!current.isEmpty()) current.append("\n\n");
                current.append(part);
            }
        }

        if (!current.isEmpty()) {
            segments.add(current.toString());
        }
        if (segments.isEmpty()) {
            segments.add(text);
        }
        return segments;
    }

    /**
     * 将文本按换行边界切分，并对单行超长内容按字符硬切，保证每个模型请求不超过 maxLen。
     */
    private static List<String> splitTextByLimit(String text, int maxLen) {
        List<String> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String rawLine : text.split("\\R", -1)) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }

            if (line.length() > maxLen) {
                if (!current.isEmpty()) {
                    segments.add(current.toString());
                    current = new StringBuilder();
                }
                for (int start = 0; start < line.length(); start += maxLen) {
                    segments.add(line.substring(start, Math.min(start + maxLen, line.length())));
                }
                continue;
            }

            if (!current.isEmpty() && current.length() + line.length() + 1 > maxLen) {
                segments.add(current.toString());
                current = new StringBuilder();
            }
            if (!current.isEmpty()) {
                current.append('\n');
            }
            current.append(line);
        }
        if (!current.isEmpty()) {
            segments.add(current.toString());
        }
        return segments;
    }
}
