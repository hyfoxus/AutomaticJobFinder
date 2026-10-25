package com.gnemirko.jobapplier.ui.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.ModelAndView;

/** The fixed error pages. Only whitelisted status codes are shown; any other code becomes 500. */
final class ErrorPages {

    private static final Map<Integer, String> TEXT = Map.of(
            400, "The request has a value this page does not accept.",
            404, "There is nothing at this address.",
            405, "This method is not available here.",
            500, "Something went wrong.");

    private ErrorPages() {
    }

    static ModelAndView page(int code) {
        int shown = TEXT.containsKey(code) ? code : 500;
        ModelAndView mv = new ModelAndView("error");
        mv.setStatus(HttpStatus.valueOf(shown));
        mv.addObject("code", shown);
        mv.addObject("text", TEXT.get(shown));
        return mv;
    }
}
