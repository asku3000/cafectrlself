package com.progameflixx.cafectrl.repository;

import com.progameflixx.cafectrl.entity.AuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Append-only by convention: the app never calls save() on an existing row or delete(), except the purge job. */
@Repository
public interface AuditEventRepository extends JpaRepository<AuditEvent, Long>, JpaSpecificationExecutor<AuditEvent> {

    List<AuditEvent> findByEntityTypeAndEntityIdOrderByIdDesc(String entityType, String entityId);

    List<AuditEvent> findByActionOrderByIdDesc(String action);
}
