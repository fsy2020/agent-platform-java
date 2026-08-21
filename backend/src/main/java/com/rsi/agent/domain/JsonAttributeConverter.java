package com.rsi.agent.domain;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * 泛型 JSON 列转换器：把 List / Map 字段透明地存成 TEXT。
 *
 * <p>用泛型基类 + TypeReference 复用序列化逻辑，避免为每种集合类型
 * 各写一份 convertToDatabaseColumn / convertToEntityAttribute。
 */
public abstract class JsonAttributeConverter<T> implements AttributeConverter<T, String> {

    /** 转换器由 JPA 反射实例化（非 Spring Bean），因此持有自己的 mapper。 */
    protected static final ObjectMapper MAPPER = new ObjectMapper();

    private final TypeReference<T> typeRef;
    private final T empty;

    protected JsonAttributeConverter(TypeReference<T> typeRef, T empty) {
        this.typeRef = typeRef;
        this.empty = empty;
    }

    @Override
    public String convertToDatabaseColumn(T attribute) {
        try {
            return MAPPER.writeValueAsString(attribute == null ? empty : attribute);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    @Override
    public T convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) return empty;
        try {
            return MAPPER.readValue(dbData, typeRef);
        } catch (Exception e) {
            return empty;
        }
    }

    @Converter
    public static class StringListConverter extends JsonAttributeConverter<List<String>> {
        public StringListConverter() { super(new TypeReference<>() {}, List.of()); }
    }

    @Converter
    public static class MapConverter extends JsonAttributeConverter<Map<String, Object>> {
        public MapConverter() { super(new TypeReference<>() {}, Map.of()); }
    }

    @Converter
    public static class MapListConverter extends JsonAttributeConverter<List<Map<String, Object>>> {
        public MapListConverter() { super(new TypeReference<>() {}, List.of()); }
    }
}
