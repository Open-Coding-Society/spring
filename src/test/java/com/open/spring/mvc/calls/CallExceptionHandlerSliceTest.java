package com.open.spring.mvc.calls;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Every MVC test slice (@WebMvcTest) builds every controller advice in the app, this one included,
 * but none of the calls beans, so PersonCapstoneApprovalRouteTest couldn't start while the advice
 * needed CallsProperties. This slice holds only the advice, so a new dependency fails here first.
 */
@WebMvcTest(useDefaultFilters = false,
        includeFilters = @Filter(type = FilterType.ASSIGNABLE_TYPE, classes = CallExceptionHandler.class))
class CallExceptionHandlerSliceTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void startsInAnMvcSliceWithoutTheCallsSettings() {
        assertThat(context.getBeanNamesForType(CallsProperties.class)).isEmpty();
        assertThat(context.getBeanNamesForType(CallExceptionHandler.class)).hasSize(1);
    }

    @Test
    void withoutTheCallsSettingsItAnswersAsIfCallsWereOff() {
        ResponseEntity<Map<String, String>> answer = context.getBean(CallExceptionHandler.class)
                .refused(new CallException(HttpStatus.FORBIDDEN, "NOT_A_MEMBER"));

        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(answer.getBody()).containsEntry("reason", "calls_disabled");
    }
}
