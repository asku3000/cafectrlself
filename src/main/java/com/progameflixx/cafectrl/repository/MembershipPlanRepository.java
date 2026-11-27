package com.progameflixx.cafectrl.repository;

import com.progameflixx.cafectrl.entity.MembershipPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MembershipPlanRepository extends JpaRepository<MembershipPlan, String> {
    List<MembershipPlan> findByCafeIdOrderBySortOrderAscNameAsc(String cafeId);

    Optional<MembershipPlan> findByIdAndCafeId(String id, String cafeId);

    boolean existsByCafeIdAndNameIgnoreCaseAndIdNot(String cafeId, String name, String id);
}
