package com.interview.agent.converter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.interview.agent.model.EvaluationReport;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.extern.slf4j.Slf4j;

/**
 * EvaluationReport ↔ JSON 字符串转换器
 *
 * @author 陈龙强
 */
@Slf4j
@Converter(autoApply = false)
public class EvaluationReportConverter implements AttributeConverter<EvaluationReport, String> {

    private static final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @Override
    public String convertToDatabaseColumn(EvaluationReport attribute) {
        if (attribute == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(attribute);
        } catch (JsonProcessingException e) {
            log.error("[EvaluationReportConverter] 序列化失败: {}", e.getMessage(), e);
            return null;
        }
    }

    @Override
    public EvaluationReport convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readValue(dbData, EvaluationReport.class);
        } catch (JsonProcessingException e) {
            log.error("[EvaluationReportConverter] 反序列化失败: {}", e.getMessage(), e);
            return null;
        }
    }
}
