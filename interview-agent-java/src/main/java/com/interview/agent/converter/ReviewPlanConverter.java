package com.interview.agent.converter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.interview.agent.model.ReviewPlan;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.extern.slf4j.Slf4j;

/**
 * ReviewPlan ↔ JSON 字符串转换器
 *
 * @author 陈龙强
 */
@Slf4j
@Converter(autoApply = false)
public class ReviewPlanConverter implements AttributeConverter<ReviewPlan, String> {

    private static final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @Override
    public String convertToDatabaseColumn(ReviewPlan attribute) {
        if (attribute == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(attribute);
        } catch (JsonProcessingException e) {
            log.error("[ReviewPlanConverter] 序列化失败: {}", e.getMessage(), e);
            return null;
        }
    }

    @Override
    public ReviewPlan convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readValue(dbData, ReviewPlan.class);
        } catch (JsonProcessingException e) {
            log.error("[ReviewPlanConverter] 反序列化失败: {}", e.getMessage(), e);
            return null;
        }
    }
}
