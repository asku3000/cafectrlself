package com.progameflixx.cafectrl.membership;

import com.progameflixx.cafectrl.dto.MembershipPlanRequest;
import com.progameflixx.cafectrl.dto.MembershipSettingsRequest;
import com.progameflixx.cafectrl.entity.MembershipPlan;
import com.progameflixx.cafectrl.entity.MembershipSettings;
import com.progameflixx.cafectrl.entity.User;
import com.progameflixx.cafectrl.service.MembershipPlanService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class MembershipPlanServiceTest {

    @Autowired
    private MembershipPlanService service;

    private final User admin = user("cafe-" + UUID.randomUUID());

    static User user(String cafeId) {
        User u = new User();
        u.setCafeId(cafeId);
        u.setName("Owner");
        u.setRole("CAFE_ADMIN");
        return u;
    }

    static MembershipPlanRequest silver() {
        MembershipPlanRequest r = new MembershipPlanRequest();
        r.setName("Silver");
        r.setLoadAmountPaise(100_000L);
        r.setBonusType("PERCENT");
        r.setBonusValue(1_000L);
        r.setValidityDays(90);
        r.setColor("#C0C0C0");
        r.setSortOrder(2);
        r.setDescription("10% extra");
        return r;
    }

    @Test
    void createsPlanWithDerivedBonusAndTotal() {
        MembershipPlan p = service.create(admin, silver());

        assertNotNull(p.getId());
        assertEquals(admin.getCafeId(), p.getCafeId());
        assertEquals(MembershipPlan.BonusType.PERCENT, p.getBonusType());
        assertEquals(10_000L, p.getBonusAmountPaise());
        assertEquals(110_000L, p.getTotalValuePaise());
        assertTrue(p.getIsActive());
        assertEquals("Owner", p.getUpdatedByName());
    }

    @Test
    void fixedBonusPlan() {
        MembershipPlanRequest r = silver();
        r.setBonusType("fixed");
        r.setBonusValue(25_000L);
        MembershipPlan p = service.create(admin, r);
        assertEquals(25_000L, p.getBonusAmountPaise());
        assertEquals(125_000L, p.getTotalValuePaise());
    }

    @Test
    void partialUpdateKeepsOtherFields() {
        MembershipPlan p = service.create(admin, silver());
        MembershipPlanRequest patch = new MembershipPlanRequest();
        patch.setValidityDays(120);

        MembershipPlan updated = service.update(admin, p.getId(), patch);

        assertEquals(120, updated.getValidityDays());
        assertEquals("Silver", updated.getName());
        assertEquals(100_000L, updated.getLoadAmountPaise());
        assertEquals(1_000L, updated.getBonusValue());
    }

    @Test
    void deactivateAndReactivate() {
        MembershipPlan p = service.create(admin, silver());
        assertFalse(service.setActive(admin, p.getId(), false).getIsActive());
        assertTrue(service.setActive(admin, p.getId(), true).getIsActive());
    }

    @Test
    void listIsScopedToCafeAndSorted() {
        MembershipPlanRequest gold = silver();
        gold.setName("Gold");
        gold.setSortOrder(1);
        service.create(admin, silver());
        service.create(admin, gold);
        service.create(user("other-cafe-" + UUID.randomUUID()), silver());

        List<MembershipPlan> plans = service.list(admin.getCafeId());
        assertEquals(List.of("Gold", "Silver"), plans.stream().map(MembershipPlan::getName).toList());
    }

    @Test
    void otherCafesPlanIsNotFound() {
        MembershipPlan p = service.create(admin, silver());
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.update(user("intruder"), p.getId(), silver()));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void rejectsInvalidPlans() {
        assertBadRequest(r -> r.setName("  "), "name is required");
        assertBadRequest(r -> r.setLoadAmountPaise(0L), "greater than zero");
        assertBadRequest(r -> r.setBonusValue(-1L), "cannot be negative");
        assertBadRequest(r -> r.setBonusValue(10_001L), "cannot exceed 100%");
        assertBadRequest(r -> r.setBonusType("DOUBLE"), "FIXED or PERCENT");
        assertBadRequest(r -> r.setValidityDays(0), "Validity");
        assertBadRequest(r -> r.setColor("red"), "hex");
    }

    @Test
    void rejectsDuplicateNameCaseInsensitive() {
        service.create(admin, silver());
        MembershipPlanRequest dup = silver();
        dup.setName("SILVER");
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.create(admin, dup));
        assertTrue(ex.getReason().contains("already exists"));
    }

    @Test
    void settingsDefaultThenUpdate() {
        MembershipSettings defaults = service.getSettings(admin.getCafeId());
        assertEquals(MembershipSettings.DeductionOrder.PAID_FIRST, defaults.getDeductionOrder());
        assertEquals(10_000L, defaults.getLowBalanceThresholdPaise());
        assertEquals("MC", defaults.getCardNumberPrefix());

        MembershipSettingsRequest req = new MembershipSettingsRequest();
        req.setDeductionOrder("bonus_first");
        req.setCardNumberPrefix("pgf");
        service.updateSettings(admin, req);

        MembershipSettings stored = service.getSettings(admin.getCafeId());
        assertEquals(MembershipSettings.DeductionOrder.BONUS_FIRST, stored.getDeductionOrder());
        assertEquals("PGF", stored.getCardNumberPrefix());
        assertEquals(10_000L, stored.getLowBalanceThresholdPaise());
    }

    @Test
    void rejectsInvalidSettings() {
        MembershipSettingsRequest req = new MembershipSettingsRequest();
        req.setCardNumberPrefix("MC-1");
        assertThrows(ResponseStatusException.class, () -> service.updateSettings(admin, req));

        MembershipSettingsRequest neg = new MembershipSettingsRequest();
        neg.setLowBalanceThresholdPaise(-1L);
        assertThrows(ResponseStatusException.class, () -> service.updateSettings(admin, neg));
    }

    private void assertBadRequest(java.util.function.Consumer<MembershipPlanRequest> mutate, String expectedMessagePart) {
        MembershipPlanRequest r = silver();
        r.setName("Plan " + UUID.randomUUID());
        mutate.accept(r);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.create(admin, r));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains(expectedMessagePart),
                () -> "expected '" + expectedMessagePart + "' in '" + ex.getReason() + "'");
    }
}
