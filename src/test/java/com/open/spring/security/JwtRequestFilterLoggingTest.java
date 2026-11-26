package com.open.spring.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.test.util.ReflectionTestUtils;

import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonDetailsService;
import com.open.spring.mvc.person.PersonJpaRepository;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.http.Cookie;

/**
 * The app logs at WARN by default (logging.level.root=warn), so whatever the filter writes at WARN
 * fills the console. Ordinary requests, signed in or not, must not write anything there; a login
 * cookie that's expired or broken still does, since that's worth seeing.
 */
class JwtRequestFilterLoggingTest {

    private static final String SECRET = Base64.getEncoder().encodeToString(new byte[64]);

    private PersonJpaRepository people;
    private JwtRequestFilter filter;
    private Logger filterLogger;
    private Level levelBefore;
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void setUp() {
        people = mock(PersonJpaRepository.class);
        Person toby = new Person();
        toby.setUid("toby");
        toby.setPassword("hashed");
        toby.setRoles(List.of("ROLE_ADMIN", "ROLE_STUDENT"));
        toby.setTokenVersion(0L);
        when(people.findByUid("toby")).thenReturn(toby);

        PersonDetailsService accounts = new PersonDetailsService();
        ReflectionTestUtils.setField(accounts, "personJpaRepository", people);
        filter = new JwtRequestFilter();
        ReflectionTestUtils.setField(filter, "personDetailsService", accounts);
        ReflectionTestUtils.setField(filter, "jwtTokenUtil", tokenUtil(3600));

        // Other tests in the same run turn this logger down; this one needs to see every level.
        filterLogger = (Logger) LoggerFactory.getLogger(JwtRequestFilter.class);
        levelBefore = filterLogger.getLevel();
        filterLogger.setLevel(Level.DEBUG);
        logs = new ListAppender<>();
        logs.start();
        filterLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        filterLogger.detachAppender(logs);
        filterLogger.setLevel(levelBefore);
        SecurityContextHolder.clearContext();
    }

    @Test
    void aSignedInApiRequestWritesNoWarning() throws Exception {
        run(request("/api/dm/unread", loginCookie(tokenUtil(3600))));

        assertEquals(List.of(), warnings());
    }

    @Test
    void aSignedOutApiRequestWritesNoWarning() throws Exception {
        run(request("/api/jokes/", null));

        assertEquals(List.of(), warnings());
    }

    @Test
    void aPageOrChatSocketRequestWritesNoWarning() throws Exception {
        run(request("/ws-chat/info", null));
        run(request("/login", null));

        assertEquals(List.of(), warnings());
    }

    @Test
    void anExpiredLoginStillWritesOneWarning() throws Exception {
        run(request("/api/dm/unread", loginCookie(tokenUtil(-60))));

        List<String> warnings = warnings();
        assertEquals(1, warnings.size(), String.valueOf(warnings));
        assertTrue(warnings.get(0).contains("expired"), warnings.get(0));
    }

    private void run(MockHttpServletRequest request) throws Exception {
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        SecurityContextHolder.clearContext();
    }

    private static MockHttpServletRequest request(String uri, Cookie cookie) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        if (cookie != null) {
            request.setCookies(cookie);
        }
        return request;
    }

    private Cookie loginCookie(JwtTokenUtil util) {
        String token = util.generateToken(
                User.withUsername("toby").password("x").authorities("ROLE_ADMIN").build(),
                List.of("ROLE_ADMIN"));
        return new Cookie("jwt_java_spring", token);
    }

    private List<String> warnings() {
        return logs.list.stream()
                .filter(event -> event.getLevel().isGreaterOrEqual(Level.WARN))
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private JwtTokenUtil tokenUtil(long validitySeconds) {
        JwtTokenUtil util = new JwtTokenUtil();
        ReflectionTestUtils.setField(util, "secret", SECRET);
        ReflectionTestUtils.setField(util, "personJpaRepository", people);
        ReflectionTestUtils.setField(util, "tokenValiditySeconds", validitySeconds);
        return util;
    }
}
