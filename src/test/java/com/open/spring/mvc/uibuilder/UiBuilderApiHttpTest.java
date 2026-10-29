package com.open.spring.mvc.uibuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.DataException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The HTTP contract of /api/ui-builder: routes, the request guard, JSON field names and status
 * codes, through both controllers and their error handler with the real service over the
 * in-memory store. Spring Security's filters are not part of a standalone MockMvc; the 401 for an
 * anonymous request to a protected route is the default /api/** rule (checked live after spring-boot:run).
 */
class UiBuilderApiHttpTest {

    private static final String ORIGIN = "http://localhost:4500";

    private final ObjectMapper json = Jackson2ObjectMapperBuilder.json().build();
    private UiBuilderTestStore store;
    private MockMvc mvc;
    private UsernamePasswordAuthenticationToken hop;
    private UsernamePasswordAuthenticationToken ada;
    private UsernamePasswordAuthenticationToken niko;

    @BeforeEach
    void setUp() {
        store = new UiBuilderTestStore();
        hop = UiBuilderTestStore.login(store.person(1, "hop", "Hop Student"), "ROLE_USER", "ROLE_STUDENT");
        ada = UiBuilderTestStore.login(store.person(2, "ada", "Ada Student"), "ROLE_USER", "ROLE_STUDENT");
        niko = UiBuilderTestStore.login(store.person(4, "niko", "Niko Teacher"), "ROLE_USER", "ROLE_TEACHER");
        store.group(40, "Binary Beasts", 1L, 2L);
        UiBuilderRequestGuard guard = new UiBuilderRequestGuard(List.of("https://pages.opencodingsociety.com", ORIGIN));
        mvc = MockMvcBuilders.standaloneSetup(
                        new UiBuilderPageApiRestController(store.service, guard),
                        new UiBuilderPublishedApiRestController(store.service, guard))
                .setControllerAdvice(new UiBuilderExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json))
                .build();
    }

    /** What the Pages store sends on every request. */
    private static MockHttpServletRequestBuilder fromPages(MockHttpServletRequestBuilder request) {
        return request.header("Origin", ORIGIN).header("X-Origin", "client").contentType(MediaType.APPLICATION_JSON);
    }

    private String createBody(String title) throws Exception {
        ObjectNode body = json.createObjectNode();
        body.put("title", title);
        body.set("spec", store.simplePage(title));
        return json.writeValueAsString(body);
    }

    private JsonNode createPage(UsernamePasswordAuthenticationToken who, String title) throws Exception {
        String response = mvc.perform(fromPages(post("/api/ui-builder/pages")).principal(who).content(createBody(title)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }

    // ---- the guard ----

    @Test
    void everySignedInRouteNeedsAnOcsPageOriginReadsIncluded() throws Exception {
        mvc.perform(get("/api/ui-builder/pages").principal(hop))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("origin_rejected"));
        mvc.perform(get("/api/ui-builder/pages").principal(hop).header("Origin", "https://student.opencodingsociety.com")
                        .header("X-Origin", "client"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/ui-builder/pages").principal(hop).header("Origin", ORIGIN))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/ui-builder/my-groups").principal(hop).header("Origin", ORIGIN).header("X-Origin", "client"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(40))
                .andExpect(jsonPath("$[0].name").value("Binary Beasts"));
        mvc.perform(get("/api/ui-builder/published/flood-watch/members/b-members01").principal(hop))
                .andExpect(status().isForbidden());
    }

    @Test
    void aWriteMustSendJson() throws Exception {
        mvc.perform(post("/api/ui-builder/pages").principal(hop).header("Origin", ORIGIN).header("X-Origin", "client")
                        .contentType(MediaType.TEXT_PLAIN).content("title=x"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("origin_rejected"));
        long id = createPage(hop, "Flood Watch").get("id").asLong();
        mvc.perform(delete("/api/ui-builder/pages/" + id).principal(hop).header("Origin", ORIGIN).header("X-Origin", "client"))
                .andExpect(status().isForbidden());
        mvc.perform(fromPages(delete("/api/ui-builder/pages/" + id)).principal(hop))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    @Test
    void theControllerRefusesAMissingLoginEvenWithoutTheSecurityChain() throws Exception {
        mvc.perform(fromPages(post("/api/ui-builder/pages")).content(createBody("x")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"));
    }

    // ---- pages ----

    @Test
    void createSaveConflictPublishAndViewKeepTheirJsonShapes() throws Exception {
        JsonNode created = createPage(hop, "Flood Watch");
        long id = created.get("id").asLong();
        List<String> fields = new ArrayList<>();
        created.fieldNames().forEachRemaining(fields::add);
        assertEquals(List.of("id", "slug", "title", "description", "byline", "course", "image", "status", "visibility",
                "publicRequested", "publicUntil", "publicPending", "publicExpired", "publicVersion", "groupId",
                "groupName", "ownerName", "isOwner", "version", "updatedAt", "updatedByName", "publishedAt",
                "publishedVersion", "hasUnpublishedChanges", "lease", "spec"), fields);
        assertTrue(created.get("slug").isNull());
        assertTrue(created.get("isOwner").asBoolean());
        assertTrue(created.get("spec").isObject());

        ObjectNode save = json.createObjectNode();
        save.put("version", 0);
        save.set("spec", store.simplePage("Flood Watch v1"));
        mvc.perform(fromPages(put("/api/ui-builder/pages/" + id)).principal(hop).content(json.writeValueAsString(save)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.title").value("Flood Watch v1"));
        mvc.perform(fromPages(put("/api/ui-builder/pages/" + id)).principal(hop).content(json.writeValueAsString(save)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("version_conflict"))
                .andExpect(jsonPath("$.currentVersion").value(1))
                .andExpect(jsonPath("$.savedByName").value("Hop Student"))
                .andExpect(jsonPath("$.savedAt").value(UiBuilderTestStore.NOW.toEpochMilli()));

        mvc.perform(fromPages(post("/api/ui-builder/pages/" + id + "/publish")).principal(hop).content("{\"version\": 1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("slug_required"));
        mvc.perform(fromPages(post("/api/ui-builder/pages/" + id + "/publish")).principal(hop)
                        .content("{\"version\": 1, \"slug\": \"flood-watch\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("flood-watch"))
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.publishedVersion").value(1));

        mvc.perform(get("/api/ui-builder/published/flood-watch").principal(ada))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Flood Watch v1"))
                .andExpect(jsonPath("$.visibility").value("SIGNED_IN"))
                .andExpect(jsonPath("$.canEdit").value(false))
                .andExpect(jsonPath("$.pageId").doesNotExist())
                .andExpect(jsonPath("$.madeBy.owner").value("Hop Student"))
                .andExpect(jsonPath("$.viewer.signedIn").value(true))
                .andExpect(jsonPath("$.viewer.name").value("Ada Student"))
                .andExpect(jsonPath("$.spec.kind").value("ocs-ui-page"));
        mvc.perform(get("/api/ui-builder/published/flood-watch").principal(hop))
                .andExpect(jsonPath("$.canEdit").value(true))
                .andExpect(jsonPath("$.pageId").value(id));
        mvc.perform(get("/api/ui-builder/published/flood-watch"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
        mvc.perform(get("/api/ui-builder/published").principal(ada))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].slug").value("flood-watch"))
                .andExpect(jsonPath("$[0].visibility").value("SIGNED_IN"));
        mvc.perform(get("/api/ui-builder/published"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void groupIdNullRemovesTheGroupButLeavingItOutKeepsIt() throws Exception {
        ObjectNode body = json.createObjectNode();
        body.set("spec", store.simplePage("Team page"));
        body.put("groupId", 40);
        String created = mvc.perform(fromPages(post("/api/ui-builder/pages")).principal(hop).content(json.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.groupId").value(40))
                .andExpect(jsonPath("$.groupName").value("Binary Beasts"))
                .andReturn().getResponse().getContentAsString();
        long id = json.readTree(created).get("id").asLong();
        String spec = json.writeValueAsString(store.simplePage("Team page"));

        mvc.perform(fromPages(put("/api/ui-builder/pages/" + id)).principal(hop)
                        .content("{\"version\": 0, \"spec\": " + spec + "}"))
                .andExpect(jsonPath("$.groupId").value(40));
        mvc.perform(fromPages(put("/api/ui-builder/pages/" + id)).principal(hop)
                        .content("{\"version\": 1, \"spec\": " + spec + ", \"groupId\": null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupId").doesNotExist());
        assertEquals(List.of(), store.editorsOf(id));
    }

    @Test
    void anApprovedPageIsPublicWithoutAnyPersonsName() throws Exception {
        long id = createPage(hop, "Flood Watch").get("id").asLong();
        mvc.perform(fromPages(post("/api/ui-builder/pages/" + id + "/publish")).principal(hop)
                .content("{\"version\": 0, \"slug\": \"flood-watch\"}")).andExpect(status().isOk());
        mvc.perform(fromPages(post("/api/ui-builder/pages/" + id + "/visibility")).principal(niko)
                        .content("{\"visibility\": \"PUBLIC\", \"publishedVersion\": 0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visibility").value("PUBLIC"))
                .andExpect(jsonPath("$.publicVersion").value(0))
                .andExpect(jsonPath("$.publicExpired").value(false));

        String body = mvc.perform(get("/api/ui-builder/published/flood-watch"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visibility").value("PUBLIC"))
                .andExpect(jsonPath("$.madeBy.owner").doesNotExist())
                .andExpect(jsonPath("$.viewer.signedIn").value(false))
                .andExpect(jsonPath("$.viewer.name").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("Hop Student"));
        assertFalse(body.contains("\"uid\""));
        mvc.perform(get("/api/ui-builder/published").param("course", "csp"))
                .andExpect(jsonPath("$[0].title").value("Flood Watch"))
                .andExpect(jsonPath("$[0].visibility").value("PUBLIC"));
    }

    @Test
    void aTeachersApprovalOfAnotherPublishedVersionIsPublishedChanged() throws Exception {
        long id = createPage(hop, "Flood Watch").get("id").asLong();
        mvc.perform(fromPages(post("/api/ui-builder/pages/" + id + "/publish")).principal(hop)
                .content("{\"version\": 0, \"slug\": \"flood-watch\"}")).andExpect(status().isOk());
        ObjectNode save = json.createObjectNode();
        save.put("version", 0);
        save.set("spec", store.simplePage("Flood Watch v1"));
        mvc.perform(fromPages(put("/api/ui-builder/pages/" + id)).principal(hop).content(json.writeValueAsString(save)))
                .andExpect(status().isOk());
        mvc.perform(fromPages(post("/api/ui-builder/pages/" + id + "/publish")).principal(hop).content("{\"version\": 1}"))
                .andExpect(jsonPath("$.publishedVersion").value(1));

        mvc.perform(fromPages(post("/api/ui-builder/pages/" + id + "/visibility")).principal(niko)
                        .content("{\"visibility\": \"PUBLIC\", \"publishedVersion\": 0}"))
                .andExpect(status().isConflict())
                .andExpect(content().json("{\"error\": \"published_changed\", \"publishedVersion\": 1}", true));
        mvc.perform(fromPages(post("/api/ui-builder/pages/" + id + "/unpublish")).principal(hop)).andExpect(status().isOk());
        // Unpublished since the review: not published, as a page never published is, not published again.
        mvc.perform(fromPages(post("/api/ui-builder/pages/" + id + "/visibility")).principal(niko)
                        .content("{\"visibility\": \"PUBLIC\", \"publishedVersion\": 1}"))
                .andExpect(status().isConflict())
                .andExpect(content().json("{\"error\": \"not_published\"}", true));
    }

    @Test
    void aSaveCanAskForARevisionOfItsOwn() throws Exception {
        long id = createPage(hop, "Flood Watch").get("id").asLong();
        ObjectNode save = json.createObjectNode();
        save.put("version", 0);
        save.set("spec", store.simplePage("Keep mine"));
        save.put("newRevision", true);

        mvc.perform(fromPages(put("/api/ui-builder/pages/" + id)).principal(hop).content(json.writeValueAsString(save)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
        assertEquals(2, store.revisionsOf(id).size(), "not merged into the create's revision");
    }

    @Test
    void aLongGalleryPictureIsAnInvalidSpecOnItsField() throws Exception {
        long id = createPage(hop, "Flood Watch").get("id").asLong();
        ObjectNode save = json.createObjectNode();
        save.put("version", 0);
        save.set("spec", store.simplePage("Flood Watch"));
        save.put("image", "https://cdn.example.com/" + "a".repeat(480));

        mvc.perform(fromPages(put("/api/ui-builder/pages/" + id)).principal(hop).content(json.writeValueAsString(save)))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"error\": \"invalid_spec\","
                        + " \"details\": [{\"nodeId\": \"page\", \"field\": \"image\", \"key\": \"too_long\"}]}", true));
    }

    @Test
    void aSecondPageOverTheOwnersLimitIsTooManyPages() throws Exception {
        store.limits.setOwnerPagesMax(1);
        createPage(hop, "One");

        mvc.perform(fromPages(post("/api/ui-builder/pages")).principal(hop).content(createBody("Two")))
                .andExpect(status().isConflict())
                .andExpect(content().json("{\"error\": \"too_many_pages\", \"max\": 1}", true));
    }

    @Test
    void onlyTheSlugsUniqueKeyIsSlugTaken() {
        UiBuilderExceptionHandler handler = new UiBuilderExceptionHandler();
        DataIntegrityViolationException mySqlSlug = new DataIntegrityViolationException("could not execute statement",
                new ConstraintViolationException("Duplicate entry 'flood-watch' for key 'ui_builder_page.slug'",
                        new SQLIntegrityConstraintViolationException("Duplicate entry 'flood-watch' for key 'ui_builder_page.slug'"),
                        "slug"));
        DataIntegrityViolationException sqliteSlug = new DataIntegrityViolationException(
                "[SQLITE_CONSTRAINT_UNIQUE] A UNIQUE constraint failed (UNIQUE constraint failed: ui_builder_page.slug)");
        DataIntegrityViolationException tooLong = new DataIntegrityViolationException("could not execute statement",
                new DataException("Data too long for column 'image' at row 1",
                        new SQLException("Data truncation: Data too long for column 'image' at row 1", "22001", 1406)));
        DataIntegrityViolationException editor = new DataIntegrityViolationException("could not execute statement",
                new ConstraintViolationException("Duplicate entry '7-2' for key 'ui_builder_page_editor.page_id'",
                        new SQLIntegrityConstraintViolationException("Duplicate entry '7-2'"), "page_id"));

        for (DataIntegrityViolationException slug : List.of(mySqlSlug, sqliteSlug)) {
            ResponseEntity<Map<String, Object>> answer = handler.integrityViolation(slug);
            assertEquals(HttpStatus.CONFLICT, answer.getStatusCode());
            assertEquals(Map.of("error", "slug_taken"), answer.getBody());
        }
        for (DataIntegrityViolationException other : List.of(tooLong, editor)) {
            ResponseEntity<Map<String, Object>> answer = handler.integrityViolation(other);
            assertEquals(HttpStatus.BAD_REQUEST, answer.getStatusCode());
            assertEquals(Map.of("error", "invalid_spec"), answer.getBody());
        }
    }

    @Test
    void errorsAreJsonWithTheirStatus() throws Exception {
        long id = createPage(hop, "Flood Watch").get("id").asLong();

        mvc.perform(fromPages(get("/api/ui-builder/pages/" + id)).principal(ada))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
        mvc.perform(fromPages(get("/api/ui-builder/pages/99999999999999999999")).principal(hop))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
        mvc.perform(fromPages(post("/api/ui-builder/pages")).principal(hop).content("{\"spec\": "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_spec"));
        mvc.perform(fromPages(post("/api/ui-builder/pages")).principal(hop)
                        .content("{\"title\": \"x\", \"spec\": " + json.writeValueAsString(store.fixture("invalid/body-script.json")) + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_spec"))
                .andExpect(jsonPath("$.details[0].nodeId").value("b-script01"))
                .andExpect(jsonPath("$.details[0].field").value("body"))
                .andExpect(jsonPath("$.details[0].key").value("bad_body"));
        mvc.perform(fromPages(put("/api/ui-builder/pages/" + id)).principal(hop)
                        .content("{\"version\": 0, \"spec\": " + json.writeValueAsString(store.simplePage("x")) + ", \"groupId\": 99}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_group"));
        mvc.perform(fromPages(get("/api/ui-builder/pages")).principal(hop).param("scope", "class"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
        mvc.perform(fromPages(get("/api/ui-builder/pages")).principal(niko).param("scope", "class").param("course", "csp"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("Flood Watch"))
                .andExpect(jsonPath("$[0].isOwner").value(false));
    }

    @Test
    void leasesRevisionsSlugsAndBulkApproval() throws Exception {
        long id = createPage(hop, "Flood Watch").get("id").asLong();
        long expires = UiBuilderTestStore.NOW.toEpochMilli() + 60_000;

        mvc.perform(fromPages(post("/api/ui-builder/pages/" + id + "/lease")).principal(hop))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"holderName\": \"Hop Student\", \"expiresAt\": " + expires + ", \"mine\": true}", true));
        mvc.perform(fromPages(post("/api/ui-builder/pages/" + id + "/lease/take")).principal(niko))
                .andExpect(jsonPath("$.holderName").value("Niko Teacher"))
                .andExpect(jsonPath("$.mine").value(true));
        mvc.perform(fromPages(get("/api/ui-builder/pages/" + id)).principal(hop))
                .andExpect(jsonPath("$.lease.holderName").value("Niko Teacher"))
                .andExpect(jsonPath("$.lease.mine").value(false));
        mvc.perform(fromPages(delete("/api/ui-builder/pages/" + id + "/lease")).principal(niko))
                .andExpect(status().isNoContent());

        mvc.perform(fromPages(get("/api/ui-builder/pages/" + id + "/revisions")).principal(hop))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].revisionId").isNumber())
                .andExpect(jsonPath("$[0].pageVersion").value(0))
                .andExpect(jsonPath("$[0].kind").value("SAVE"))
                .andExpect(jsonPath("$[0].savedByName").value("Hop Student"))
                .andExpect(jsonPath("$[0].spec").doesNotExist());
        long revision = store.revisionsOf(id).get(0).getId();
        mvc.perform(fromPages(get("/api/ui-builder/pages/" + id + "/revisions/" + revision)).principal(hop))
                .andExpect(jsonPath("$.spec.page.title").value("Flood Watch"));

        mvc.perform(fromPages(get("/api/ui-builder/slug-available")).principal(hop).param("slug", "admin"))
                .andExpect(content().json("{\"slug\": \"admin\", \"available\": false, \"reason\": \"reserved\"}", true));
        mvc.perform(fromPages(get("/api/ui-builder/slug-available")).principal(hop).param("slug", "flood-watch"))
                .andExpect(content().json("{\"slug\": \"flood-watch\", \"available\": true}", true));

        mvc.perform(fromPages(post("/api/ui-builder/pages/" + id + "/publish")).principal(hop)
                .content("{\"version\": 0, \"slug\": \"flood-watch\"}")).andExpect(status().isOk());
        mvc.perform(fromPages(post("/api/ui-builder/pages/visibility/bulk")).principal(hop)
                        .content("{\"course\": \"csp\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(fromPages(post("/api/ui-builder/pages/visibility/bulk")).principal(niko)
                        .content("{\"course\": \"csp\"}"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"updated\": 1}", true));
    }

    @Test
    void membersAndMessageOwnerAreForSignedInViewers() throws Exception {
        store.group(42, "Binary Beasts", 1L, 2L);
        ObjectNode body = json.createObjectNode();
        body.set("spec", store.fixture("all-blocks.json"));
        String created = mvc.perform(fromPages(post("/api/ui-builder/pages")).principal(hop).content(json.writeValueAsString(body)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id = json.readTree(created).get("id").asLong();
        mvc.perform(fromPages(post("/api/ui-builder/pages/" + id + "/publish")).principal(hop)
                .content("{\"version\": 0, \"slug\": \"flood-watch\"}")).andExpect(status().isOk());

        mvc.perform(fromPages(get("/api/ui-builder/published/flood-watch/members/b-members01")).principal(ada))
                .andExpect(status().isOk())
                .andExpect(content().json("[{\"name\": \"Ada Student\"}, {\"name\": \"Hop Student\"}]", true));
        mvc.perform(fromPages(post("/api/ui-builder/published/flood-watch/message-owner")).principal(ada))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId").value(100));
        mvc.perform(fromPages(post("/api/ui-builder/published/flood-watch/message-owner")).principal(hop))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("own_page"));
        mvc.perform(get("/api/ui-builder/published/flood-watch").principal(ada))
                .andExpect(jsonPath("$.groups['42'].name").value("Binary Beasts"))
                .andExpect(jsonPath("$.groups['42'].viewerIsMember").value(true));
    }

    // ---- the request-size filter ----

    @Test
    void bodiesOverAMillionBytesAreRefusedBeforeTheyAreRead() throws Exception {
        UiBuilderRequestSizeFilter filter = new UiBuilderRequestSizeFilter();

        MockHttpServletResponse declared = run(filter, request("/api/ui-builder/pages", new byte[16], 1_000_001L));
        assertEquals(413, declared.getStatus());
        assertEquals("{\"error\":\"too_large\"}", declared.getContentAsString());

        MockHttpServletResponse chunked = run(filter, request("/api/ui-builder/pages", new byte[1_000_001], -1));
        assertEquals(413, chunked.getStatus());

        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse small = new MockHttpServletResponse();
        filter.doFilter(request("/api/ui-builder/pages", "{\"a\":1}".getBytes(), -1), small, chain);
        assertEquals(200, small.getStatus());
        assertEquals("{\"a\":1}", new String(chain.getRequest().getInputStream().readAllBytes()));

        MockHttpServletResponse elsewhere = run(filter, request("/api/files/upload", new byte[16], 2_000_000L));
        assertEquals(200, elsewhere.getStatus(), "other routes keep their own limits");
    }

    @Test
    void aPercentEncodedPathToTheSameRoutesGetsTheSameLimit() throws Exception {
        UiBuilderRequestSizeFilter filter = new UiBuilderRequestSizeFilter();

        for (String uri : List.of("/api/ui%2Dbuilder/pages", "/api/%75i-builder/pages", "/api/ui-builder%2Fpages")) {
            assertEquals(413, run(filter, request(uri, new byte[16], 1_000_001L)).getStatus(), uri);
            assertEquals(413, run(filter, request(uri, new byte[1_000_001], -1)).getStatus(), uri + " chunked");
        }
        assertEquals(200, run(filter, request("/api/files%2Dupload", new byte[16], 2_000_000L)).getStatus());
    }

    private static MockHttpServletRequest request(String uri, byte[] body, long contentLength) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri) {
            @Override
            public long getContentLengthLong() {
                return contentLength;
            }

            @Override
            public int getContentLength() {
                return (int) contentLength;
            }
        };
        request.setContent(body);
        if (contentLength < 0) {
            request.addHeader("Transfer-Encoding", "chunked");
        }
        return request;
    }

    private static MockHttpServletResponse run(UiBuilderRequestSizeFilter filter, MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}
