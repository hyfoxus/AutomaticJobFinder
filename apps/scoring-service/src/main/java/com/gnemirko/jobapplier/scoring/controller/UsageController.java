package com.gnemirko.jobapplier.scoring.controller;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gnemirko.jobapplier.scoring.config.ScoringEnabled;
import com.gnemirko.jobapplier.scoring.dto.UsageView;
import com.gnemirko.jobapplier.scoring.repository.LlmUsageRepository;
import com.gnemirko.jobapplier.scoring.service.BudgetGuard;
import com.gnemirko.jobapplier.scoring.service.UsageLedger;

import lombok.RequiredArgsConstructor;

@RestController
@ScoringEnabled
@RequiredArgsConstructor
@RequestMapping(path = "/api/usage", produces = MediaType.APPLICATION_JSON_VALUE)
public class UsageController {

    private final UsageLedger ledger;
    private final BudgetGuard budget;
    private final LlmUsageRepository usage;

    @GetMapping("/today")
    public UsageView today() {
        var day = ledger.today();
        long spent = ledger.spentToday();
        long cap = budget.capMicros();
        return UsageView.of(day, spent, cap, budget.state(spent, spent >= cap), usage.countByDay(day));
    }
}
