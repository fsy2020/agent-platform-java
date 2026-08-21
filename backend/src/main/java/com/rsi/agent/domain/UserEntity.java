package com.rsi.agent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "users")
public class UserEntity extends BaseEntity {

    @Column(unique = true, nullable = false, length = 64)
    private String username;

    @Column(nullable = false)
    private String passwordHash;

    private String displayName = "";

    /** admin | developer | viewer */
    private String role = "developer";

    private String tenantId = "default";
    private boolean active = true;

    public String getUsername() { return username; }
    public void setUsername(String v) { this.username = v; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String v) { this.passwordHash = v; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String v) { this.displayName = v; }
    public String getRole() { return role; }
    public void setRole(String v) { this.role = v; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { this.tenantId = v; }
    public boolean isActive() { return active; }
    public void setActive(boolean v) { this.active = v; }
}
