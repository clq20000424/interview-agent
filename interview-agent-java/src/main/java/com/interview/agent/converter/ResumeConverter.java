package com.interview.agent.converter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.interview.agent.model.Resume;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.extern.slf4j.Slf4j;

/**
 * Resume ↔ JSON 字符串转换器
 *
 * @author 陈龙强
 */
@Slf4j
@Converter(autoApply = false)
public class ResumeConverter implements AttributeConverter<Resume, String> {

    private static final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @Override
    public String convertToDatabaseColumn(Resume attribute) {
        if (attribute == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(attribute);
        } catch (JsonProcessingException e) {
            log.error("[ResumeConverter] 序列化失败: {}", e.getMessage(), e);
            return null;
        }
    }

    @Override
    public Resume convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readValue(dbData, Resume.class);
        } catch (JsonProcessingException e) {
            log.error("[ResumeConverter] 反序列化失败: {}", e.getMessage(), e);
            return null;
        }
    }
}
