package com.gnemirko.jobapplier.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.web.servlet.MockMvc;

/** Default settings: the context starts and no bean of the UI exists. */
@SpringBootTest
@AutoConfigureMockMvc
class UiDisabledTest {

    @Autowired ConfigurableApplicationContext ctx;
    @Autowired MockMvc mvc;

    @Test
    void noUiBeansAndNoPages() throws Exception {
        for (String name : ctx.getBeanDefinitionNames()) {
            Class<?> type = ctx.getType(name);
            String cls = type == null ? "" : type.getName();
            // The application class itself is the only bean of the root package.
            if (!cls.equals("com.gnemirko.jobapplier.ui.WebUiApplication")) {
                assertThat(cls).as(name).doesNotStartWith("com.gnemirko.jobapplier.ui.");
            }
        }
        mvc.perform(get("/")).andExpect(status().isNotFound());
        mvc.perform(get("/assessments")).andExpect(status().isNotFound());
    }
}
