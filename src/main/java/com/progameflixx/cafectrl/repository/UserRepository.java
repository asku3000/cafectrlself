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
}