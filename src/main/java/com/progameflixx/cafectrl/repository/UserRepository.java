package com.progameflixx.cafectrl.repository;

import com.progameflixx.cafectrl.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.List;

@Repository
public interface UserRepository extends JpaRepository<User, String> {

    Optional<User> findByEmail(String email);
    List<User> findByCafeIdAndRole(String cafeId, String role);

    boolean existsByEmailAndIdNot(String email, String id);

    /** Row-locks it for the rest of the transaction, so two concurrent edits/deletes are applied one after the other. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT u FROM User u WHERE u.id = :id")
    java.util.Optional<User> findForUpdate(@org.springframework.data.repository.query.Param("id") String id);
}