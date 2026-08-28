package com.edevlet.lineage.domain.repository;

import com.edevlet.lineage.domain.model.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface TransactionalOutboxRepository extends JpaRepository<OutboxEvent, UUID> {
}
