package com.gnemirko.jobapplier.scoring.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.gnemirko.jobapplier.scoring.controller.LlmCompleteController;
import com.gnemirko.jobapplier.scoring.controller.RunController;
import com.gnemirko.jobapplier.scoring.exception.BadRequestException;
import com.gnemirko.jobapplier.scoring.exception.LearnForbiddenException;
import com.gnemirko.jobapplier.scoring.exception.PayloadTooLargeException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Checks for the learn endpoint that must run before the body is read: token (403), run header (400),
 * declared Content-Length (413). The Host guard is a filter and has already run. A body without a
 * length is bounded by the controller's own read.
 */
@Component
@LearnEnabled
public class LearnGuardInterceptor implements HandlerInterceptor {

    private final LearnProperties learn;
    private final byte[] token;

    public LearnGuardInterceptor(LearnProperties learn) {
        this.learn = learn;
        this.token = learn.cleanToken().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String supplied = request.getHeader(LlmCompleteController.TOKEN_HEADER);
        if (supplied == null || !MessageDigest.isEqual(token, supplied.getBytes(StandardCharsets.UTF_8))) {
            throw new LearnForbiddenException();
        }
        if (!"1".equals(request.getHeader(RunController.HEADER))) {
            throw new BadRequestException("header " + RunController.HEADER + ": 1 is required");
        }
        if (request.getContentLengthLong() > LlmCompleteController.bodyCap(learn)) {
            throw new PayloadTooLargeException("request is too large");
        }
        return true;
    }
}
