package com.dju.labflow.project.project.infrastructure.persistence;

import com.dju.labflow.global.id.AssignedIdEntity;
import com.dju.labflow.project.project.domain.ProjectVisibility;
import jakarta.persistence.Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Version;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Index;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** 프로젝트 저장 모델. 변경 가능한 필드와 낙관적 잠금 버전을 관리한다. */
@Entity
@Table(name = "project_entity", indexes = @Index(name = "idx_project_created_by", columnList = "created_by"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProjectEntity extends AssignedIdEntity {

    @Version
    private Long version;
    @Column(nullable = false, length = 100)
    private String name;
    @Column(length = 2000)
    private String description;
    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ProjectVisibility visibility;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;
    private Instant updatedAt;

    @Builder
    public ProjectEntity(Long id, String name, String description, Long createdBy, ProjectVisibility visibility, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.createdBy = createdBy;
        this.visibility = visibility;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** 관리 중인 엔티티의 변경 감지로 수정 내용을 저장한다. */
    public void update(String name, String description, ProjectVisibility visibility, Instant updatedAt) {
        this.name = name;
        this.description = description;
        this.visibility = visibility;
        this.updatedAt = updatedAt;
    }
}
