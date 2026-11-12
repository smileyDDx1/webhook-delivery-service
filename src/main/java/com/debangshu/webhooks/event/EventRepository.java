package com.debangshu.webhooks.event;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

public interface EventRepository extends JpaRepository<Event, Long>, JpaSpecificationExecutor<Event> {

    Optional<Event> findByIdAndApplicationId(Long id, Long applicationId);

    Optional<Event> findByApplicationIdAndIdempotencyKey(Long applicationId, String idempotencyKey);

    long countByApplicationId(Long applicationId);
}
