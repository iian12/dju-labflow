package com.dju.labflow.sdk.request.infrastructure;

import com.dju.labflow.project.project.domain.ProjectId;
import com.dju.labflow.sdk.request.domain.SdkRequest;
import com.dju.labflow.sdk.request.domain.SdkRequestRepository;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 성공한 쓰기와 동일한 트랜잭션이 없으면 기록을 저장할 수 없다. */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class SdkRequestRepositoryImpl implements SdkRequestRepository {
    private final SdkRequestJpaRepository repository;

    public SdkRequestRepositoryImpl(SdkRequestJpaRepository repository) {
        this.repository = repository;
    }

    public Optional<SdkRequest> find(ProjectId projectId, String requestKey) {
        return repository
                .findByProjectIdAndRequestKey(projectId.value(), requestKey)
                .map(SdkRequestEntity::toDomain);
    }

    public void save(SdkRequest request) {
        repository.save(new SdkRequestEntity(request));
    }
}
