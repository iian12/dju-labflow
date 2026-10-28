package com.dju.labflow.global.id;

import jakarta.persistence.*;
import org.springframework.data.domain.Persistable;

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
    protected void markNotNew() {
        newEntity = false;
    }
}
