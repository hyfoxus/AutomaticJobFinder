package com.gnemirko.jobapplier.ui.controller;

import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.ModelAndView;

import com.gnemirko.jobapplier.ui.config.UiEnabled;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;

/** Container-level errors (for example a malformed URL) get the same fixed pages; the details are never shown. */
@Controller
@UiEnabled
public class UiErrorController implements ErrorController {

    @RequestMapping("/error")
    public ModelAndView error(HttpServletRequest request) {
        Object code = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        return ErrorPages.page(code instanceof Integer i ? i : 500);
    }
}
