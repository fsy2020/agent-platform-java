package com.rsi.agent.domain;

import java.util.Map;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "messages")
public class MessageEntity extends BaseEntity {

    @Column(nullable = false, length = 32)
    private String sessionId;

    /** user | assistant | system | tool */
    @Column(nullable = false, length = 16)
    private String role;

    @Column(length = 16000)
    private String content = "";

    @Convert(converter = JsonAttributeConverter.MapConverter.class)
    @Column(length = 4000)
    private Map<String, Object> meta = Map.of();

    public String getSessionId() { return sessionId; }
    public void setSessionId(String v) { this.sessionId = v; }
    public String getRole() { return role; }
    public void setRole(String v) { this.role = v; }
    public String getContent() { return content; }
    public void setContent(String v) { this.content = v; }
    public Map<String, Object> getMeta() { return meta; }
    public void setMeta(Map<String, Object> v) { this.meta = v; }
}
