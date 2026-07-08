package com.interview.agent.converter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.extern.slf4j.Slf4j;

/**
 * JPA 属性转换器：将复杂对象转换为 JSON 字符串存储到数据库
 * 
 * @author 陈龙强
 */
@Slf4j
@Converter
public class JsonAttributeConverter implements AttributeConverter<Object, String> {

    private static final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @Override
    public String convertToDatabaseColumn(Object attribute) {
        if (attribute == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(attribute);
        } catch (JsonProcessingException e) {
            log.error("[JsonConverter] 序列化失败: {}", e.getMessage(), e);
            return null;
        }
    }

    @Override
    public Object convertToEntityAttribute(String dbData) {
        // 注意：由于泛型擦除，这里无法知道目标类型
        // 这个转换器需要配合具体类型的转换器使用
        return dbData;
    }
}
