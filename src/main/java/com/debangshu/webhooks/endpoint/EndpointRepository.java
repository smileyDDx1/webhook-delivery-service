package com.debangshu.webhooks.endpoint;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EndpointRepository extends JpaRepository<Endpoint, Long> {

    // every lookup includes the application id, so one application can never load another one's endpoint
    Optional<Endpoint> findByIdAndApplicationId(Long id, Long applicationId);

    boolean existsByIdAndApplicationId(Long id, Long applicationId);

    Page<Endpoint> findByApplicationId(Long applicationId, Pageable pageable);

    List<Endpoint> findByApplicationIdAndStatus(Long applicationId, EndpointStatus status);
}
