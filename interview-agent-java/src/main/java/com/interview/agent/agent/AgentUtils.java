package com.interview.agent.agent;

/**
 * Agent 通用工具方法
 *
 * @author 陈龙强
 */
public class AgentUtils {

    /**
     * 从 LLM 响应文本中提取一个完整的 JSON 对象或数组。
     *
     * <p>不使用首尾括号截取：JSON 字符串本身可能包含大括号，且模型输出可能被截断。
     * 扫描时按括号类型维护嵌套深度，并忽略字符串中的括号和转义字符。</p>
     */
    public static String extractJSON(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("LLM 响应内容为空");
        }

        int start = -1;
        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            if (current == '{' || current == '[') {
                start = i;
                break;
            }
        }

        if (start == -1) {
            throw new IllegalArgumentException("LLM 响应中未找到 JSON 起始符");
        }

        char opening = text.charAt(start);
        java.util.ArrayDeque<Character> brackets = new java.util.ArrayDeque<>();
        brackets.push(opening);
        boolean inString = false;
        boolean escaped = false;

        for (int i = start + 1; i < text.length(); i++) {
            char current = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    inString = false;
                }
                continue;
            }
            if (current == '"') {
                inString = true;
            } else if (current == '{' || current == '[') {
                brackets.push(current);
            } else if (current == '}' || current == ']') {
                Character expectedOpening = brackets.peek();
                if (expectedOpening == null || !isMatchingBracket(expectedOpening, current)) {
                    throw new IllegalArgumentException("LLM 响应中的 JSON 括号类型不匹配");
                }
                brackets.pop();
                if (brackets.isEmpty()) {
                    return text.substring(start, i + 1);
                }
            }
        }

        throw new IllegalArgumentException("LLM 响应中的 JSON 结构不完整");
    }

    private static boolean isMatchingBracket(char opening, char closing) {
        return (opening == '{' && closing == '}') || (opening == '[' && closing == ']');
    }
}
