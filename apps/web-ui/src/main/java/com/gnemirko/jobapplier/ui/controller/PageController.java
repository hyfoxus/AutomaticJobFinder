package com.gnemirko.jobapplier.ui.controller;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.gnemirko.jobapplier.ui.config.UiEnabled;
import com.gnemirko.jobapplier.ui.dto.AssessmentStatus;
import com.gnemirko.jobapplier.ui.dto.Tier;
import com.gnemirko.jobapplier.ui.exception.BadParameterException;
import com.gnemirko.jobapplier.ui.service.CsrfService;
import com.gnemirko.jobapplier.ui.service.PageService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

@Controller
@UiEnabled
@RequiredArgsConstructor
public class PageController {

    static final int MAX_PAGE = PageService.LAST_PAGE_WITH_NEXT;

    /** The only texts {@code msg} can show; anything else shows nothing. */
    private static final Map<String, String> MESSAGES = Map.of(
            "started", "A scoring run was started.",
            "busy", "A scoring run is already active, or one was started a moment ago.",
            "failed", "The scoring run could not be started.");

    private final PageService pages;
    private final CsrfService csrf;

    @GetMapping("/")
    public String dashboard(@RequestParam(required = false) String msg, HttpServletRequest request,
                            HttpServletResponse response, Model model) {
        model.addAttribute("view", pages.dashboard());
        model.addAttribute("message", msg == null ? null : MESSAGES.get(msg));
        model.addAttribute("csrf", csrf.tokenFor(request, response));
        return "dashboard";
    }

    @GetMapping("/assessments")
    public String assessments(@RequestParam(required = false) String status, @RequestParam(required = false) String tier,
                              @RequestParam(required = false) String page, Model model) {
        AssessmentStatus st = status == null || status.isBlank() ? AssessmentStatus.SCORED
                : enumOf(AssessmentStatus.class, status);
        Tier tr = tier == null || tier.isBlank() ? null : enumOf(Tier.class, tier);
        if (tr != null && st != AssessmentStatus.SCORED) {
            throw new BadParameterException("tier needs status SCORED");
        }
        model.addAttribute("view", pages.list(st, tr, pageOf(page)));
        model.addAttribute("statuses", AssessmentStatus.values());
        model.addAttribute("tiers", Tier.values());
        return "assessments";
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, String raw) {
        String s = raw.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(type.getEnumConstants()).filter(e -> e.name().equals(s)).findFirst()
                .orElseThrow(() -> new BadParameterException("unknown value"));
    }

    private static int pageOf(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        String s = raw.trim();
        if (s.length() > 3 || !s.chars().allMatch(c -> c >= '0' && c <= '9')) {
            throw new BadParameterException("bad page");
        }
        int n = Integer.parseInt(s);
        if (n > MAX_PAGE) {
            throw new BadParameterException("bad page");
        }
        return n;
    }
}
