package com.chala.posapp.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * One platform-wide setting.
 *
 * <p>Key/value rather than a column per setting: these get added often, and a migration for
 * every new checkbox is not worth it. Type coercion happens in
 * {@code PlatformSettingsService}, which is also the only place that knows the valid keys.
 */
@Entity
@Table(name = "platform_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlatformSetting {

    @Id
    @Column(name = "setting_key", nullable = false, length = 80)
    private String key;

    @Lob
    @Column(name = "setting_value", columnDefinition = "TEXT")
    private String value;

    @Column(name = "updated_by", length = 80)
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = LocalDateTime.now();
    }
}
