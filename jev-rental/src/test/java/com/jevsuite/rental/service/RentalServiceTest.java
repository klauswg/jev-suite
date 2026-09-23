package com.jevsuite.rental.service;

import com.jevsuite.kit.client.MockJevClient;
import com.jevsuite.rental.domain.ChecklistReport;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RentalServiceTest {

    private final RentalService svc = new RentalService(new MockJevClient(Map.of("claim:", 0.85), 0.5));

    @Test
    void shortTextReturnsInsufficientInfo() {
        ChecklistReport r = svc.analyze("好房", "北京", 3000);
        assertEquals("INSUFFICIENT_INFO", r.status());
        assertEquals(3, r.items().size());
        assertTrue(r.items().stream().allMatch(i -> "NEED_EVIDENCE".equals(i.claimClass())));
    }

    @Test
    void rentOutlierPrependsHighRiskItem() {
        ChecklistReport r = svc.analyze("朝南主卧，家电齐全，拎包入住，随时看房。", "北京", 800);
        assertEquals(Boolean.TRUE, r.rentOutlier());
        ChecklistReport.CheckItem first = r.items().get(0);
        assertEquals("HIGH_RISK", first.claimClass());
        assertEquals("low_price_bait", first.riskReason());
        assertEquals("fees", first.category());
    }

    @Test
    void normalRentIsNotOutlierAndSortedByRoute() {
        ChecklistReport r = svc.analyze("家电齐全，冰箱洗衣机都有。房东直租，无中介费。采光好，南北通透。", "成都", 2000);
        assertEquals(Boolean.FALSE, r.rentOutlier());
        assertEquals("READY", r.status());
        assertFalse(r.items().isEmpty());
        // 动线序非递减
        for (int i = 1; i < r.items().size(); i++) {
            assertTrue(RentalService.categoryOrder(r.items().get(i - 1).category())
                    <= RentalService.categoryOrder(r.items().get(i).category()));
        }
    }

    @Test
    void nullCityFallsBackToDefaultRange() {
        assertFalse(RentRanges.isOutlier(null, 2000));
        assertTrue(RentRanges.isOutlier(null, 200));
    }
}
