package com.debangshu.webhooks.delivery;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DeliveryRepository extends JpaRepository<Delivery, Long>, JpaSpecificationExecutor<Delivery> {

    Optional<Delivery> findByIdAndApplicationId(Long id, Long applicationId);

    long countByEventId(Long eventId);

    List<Delivery> findByEventIdOrderByIdAsc(Long eventId);

    /**
     * The delivery together with its event and endpoint in a single query (a join), instead of
     * 1 query + 2 lazy loads. The worker needs all three to build the HTTP request.
     */
    @EntityGraph(attributePaths = {"event", "endpoint"})
    @Query("select d from Delivery d where d.id = :id")
    Optional<Delivery> findWithEventAndEndpoint(@Param("id") Long id);

    @Query("select d.eventId as eventId, count(d) as total from Delivery d where d.eventId in :eventIds group by d.eventId")
    List<EventDeliveryCount> countByEventIds(@Param("eventIds") Collection<Long> eventIds);

    interface EventDeliveryCount {
        Long getEventId();

        long getTotal();
    }
}
