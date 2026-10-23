package com.open.spring.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.*;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import com.open.spring.mvc.person.PersonDetailsService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;

/** Exercises the real security chain without booting the app or connecting to a database. */
class ApiAuthorizationTest {
    static AnnotationConfigWebApplicationContext context;
    static FilterChainProxy filters;

    @BeforeAll
    static void setup() throws Exception {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        // Register finished mocks so Spring does not inject their real service fields.
        context.addBeanFactoryPostProcessor(factory -> {
            var fixtures = new TestConfig();
            factory.registerSingleton("people", fixtures.people());
            factory.registerSingleton("tokens", fixtures.tokens());
            try {
                factory.registerSingleton("rateLimit", fixtures.rateLimit());
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });
        context.register(TestConfig.class);
        context.refresh();
        filters = context.getBean(FilterChainProxy.class);
    }

    @AfterAll
    static void close() { context.close(); }

    @AfterEach
    void clearContext() { SecurityContextHolder.clearContext(); }

    static Stream<String> formerlyPublic() {
        return Stream.of("POST /api/grades", "POST /api/grades/", "GET /api/grades/read-gist/123",
            "GET /api/person/uid/student", "POST /api/face/register/public",
            "POST /api/submissions/submit/1", "POST /api/submissions/1/assigned-graders",
            "POST /api/assignment-submissions/upload", "GET /api/make/status",
            "POST /api/make/run", "GET /api/pausemenu/", "POST /api/pausemenu/",
            "GET /api/leaderboard/", "POST /api/leaderboard/", "GET /api/jokes/",
            "GET /api/future-endpoint", "PATCH /api/future-endpoint",
            "DELETE /api/person/create", "PUT /api/person/create", "GET /api", "GET /authenticate");
    }

    @ParameterizedTest
    @MethodSource("formerlyPublic")
    void anonymousRequestsCannotReachFeatureApis(String route) throws Exception {
        var response = request(route, null);
        assertEquals(401, response.getStatus(), route);
        assertEquals("application/json;charset=UTF-8", response.getContentType());
        assertNull(response.getRedirectedUrl());
    }

    @Test
    void loginSignupAndPreflightRemainPublic() throws Exception {
        for (String route : List.of("POST /authenticate", "POST /api/person/create", "OPTIONS /api/grades")) {
            assertEquals(204, request(route, null).getStatus(), route);
        }
        assertEquals(401, request("GET /api/person/create", null).getStatus());
    }

    @Test
    void validJwtAllowsFeatureAccessButPreservesRoleBoundaries() throws Exception {
        assertEquals(204, request("POST /api/grades", "ROLE_STUDENT").getStatus());
        assertEquals(204, request("POST /api/assignment-submissions/upload", "ROLE_STUDENT").getStatus());
        assertEquals(403, request("DELETE /api/person/1", "ROLE_STUDENT").getStatus());
        assertEquals(403, request("GET /api/face/faces", "ROLE_STUDENT").getStatus());
        assertEquals(403, request("POST /api/face/register/public", "ROLE_STUDENT").getStatus());
        assertEquals(204, request("GET /api/face/faces", "ROLE_TEACHER").getStatus());
        assertEquals(204, request("DELETE /api/person/1", "ROLE_ADMIN").getStatus());
        assertEquals(403, request("GET /api/future-endpoint", "ROLE_ASSIGNMENT_SYNC").getStatus());
        assertEquals(204, request("POST /api/assignments/auto-create", "ROLE_ASSIGNMENT_SYNC").getStatus());
    }

    @Test
    void invalidJwtCannotReachProtectedEndpoints() throws Exception {
        assertEquals(401, request("GET /api/jokes/", "invalid").getStatus());
        assertEquals(204, request("POST /api/person/create", "invalid").getStatus());
    }

    private MockHttpServletResponse request(String route, String role) throws Exception {
        String[] parts = route.split(" ", 2);
        var request = new MockHttpServletRequest(context.getServletContext(), parts[0], parts[1]);
        request.setServletPath(parts[1]);
        if (role != null) request.setCookies(new Cookie("jwt_java_spring", role));
        var response = new MockHttpServletResponse();
        filters.doFilter(request, response, (req, res) -> ((jakarta.servlet.http.HttpServletResponse) res).setStatus(204));
        assertNull(request.getSession(false), "API authorization must remain stateless");
        return response;
    }

    @Configuration
    @EnableWebSecurity
    @EnableWebMvc
    @Import({SecurityConfig.class, JwtRequestFilter.class, JwtAuthenticationEntryPoint.class})
    static class TestConfig {
        PersonDetailsService people() {
            var people = mock(PersonDetailsService.class);
            when(people.loadUserByUsername(any())).thenAnswer(invocation ->
                User.withUsername("student").password("unused").authorities((String) invocation.getArgument(0)).build());
            return people;
        }

        JwtTokenUtil tokens() {
            var tokens = mock(JwtTokenUtil.class);
            when(tokens.getUsernameFromToken(any())).thenAnswer(invocation -> invocation.getArgument(0));
            when(tokens.validateToken(any(), any())).thenAnswer(invocation -> !"invalid".equals(invocation.getArgument(0)));
            return tokens;
        }

        RateLimitFilter rateLimit() throws Exception {
            var filter = mock(RateLimitFilter.class);
            doAnswer(invocation -> {
                ((FilterChain) invocation.getArgument(2)).doFilter(invocation.getArgument(0), invocation.getArgument(1));
                return null;
            }).when(filter).doFilter(any(), any(), any());
            return filter;
        }
    }
}
