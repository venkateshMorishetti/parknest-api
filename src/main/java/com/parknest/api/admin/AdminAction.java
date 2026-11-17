package com.parknest.api.admin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Audit log (table admin_actions): who did what to what, and why. metadata.label is the target's name at the time. */
@Entity
@Table(name = "admin_actions")
@Getter
@Setter
@NoArgsConstructor
public class AdminAction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String actorId;
    private String action;
    private String targetType;
    private String targetId;
    private String reason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata = new HashMap<>();

    private OffsetDateTime createdAt;

    public AdminAction(String actorId, String action, String targetType, String targetId, String label, String reason) {
        this.actorId = actorId;
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.reason = reason;
        this.metadata = new HashMap<>(Map.of("label", label == null ? targetId : label));
    }

    @PrePersist
    void onCreate() {
        createdAt = OffsetDateTime.now();
    }
}
