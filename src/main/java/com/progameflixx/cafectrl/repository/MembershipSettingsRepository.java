package com.progameflixx.cafectrl.repository;

import com.progameflixx.cafectrl.entity.MembershipSettings;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface MembershipSettingsRepository extends JpaRepository<MembershipSettings, String> {

    /** Row-locks the settings row; used to hand out sequential card numbers safely. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM MembershipSettings s WHERE s.cafeId = :cafeId")
    Optional<MembershipSettings> findForUpdate(@Param("cafeId") String cafeId);
}
