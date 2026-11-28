package com.progameflixx.cafectrl.repository;

import com.progameflixx.cafectrl.entity.Resource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ResourceRepository extends JpaRepository<Resource, String> {
    Optional<Resource> findByIdAndCafeId(String id, String cafeId);
    List<Resource> findByCafeId(String cafeId);

    /** Row-locks the station while a session / game is started on it, so two starts can't both pass the busy check. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT r FROM Resource r WHERE r.id = :id")
    Optional<Resource> findForUpdate(@org.springframework.data.repository.query.Param("id") String id);
}