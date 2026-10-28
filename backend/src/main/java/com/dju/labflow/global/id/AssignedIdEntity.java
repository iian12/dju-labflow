package com.dju.labflow.global.id;

import jakarta.persistence.*;
import org.springframework.data.domain.Persistable;

/** 애플리케이션에서 미리 할당한 ID를 사용하는 엔티티의 신규 여부를 관리한다. */
@MappedSuperclass
public abstract class AssignedIdEntity implements Persistable<Long> {

    @Id
    @Column(nullable = false, updatable = false)
    protected Long id;

    @Transient private boolean newEntity = true;

    @Override
    public Long getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return newEntity;
    }

    @PostLoad
    @PostPersist
    protected void markNotNew() {
        newEntity = false;
    }
}
