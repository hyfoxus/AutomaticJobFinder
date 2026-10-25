package com.gnemirko.jobapplier.ui.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;

import com.gnemirko.jobapplier.ui.config.UiEnabled;

import lombok.extern.slf4j.Slf4j;

/** Every failure becomes a fixed page with its own status. No exception text reaches the page. */
@Slf4j
@ControllerAdvice
@UiEnabled
public class UiErrorAdvice {

    @ExceptionHandler({com.gnemirko.jobapplier.ui.exception.BadParameterException.class,
            org.springframework.web.bind.ServletRequestBindingException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ModelAndView badRequest(Exception e) {
        return page(HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    ModelAndView notFound(Exception e) {
        return page(HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    ModelAndView methodNotAllowed(Exception e) {
        return page(HttpStatus.METHOD_NOT_ALLOWED);
    }

    @ExceptionHandler(Exception.class)
    ModelAndView other(Exception e) {
        log.warn("Unhandled {}", e.getClass().getSimpleName());
        return page(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private static ModelAndView page(HttpStatus status) {
        return ErrorPages.page(status.value());
    }
}
