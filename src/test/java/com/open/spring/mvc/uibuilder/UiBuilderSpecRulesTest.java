package com.open.spring.mvc.uibuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.open.spring.mvc.uibuilder.UiBuilderSpecRules.GroupRef;
import com.open.spring.mvc.uibuilder.UiBuilderSpecRules.Issue;
import com.open.spring.mvc.uibuilder.UiBuilderSpecRules.Mode;
import com.open.spring.mvc.uibuilder.UiBuilderSpecRules.PageFields;

/** The page document rules, checked against the fixtures the Pages validator shares. */
class UiBuilderSpecRulesTest {

    /** Every block type in the design's registry: content, talk and plan, and your project. */
    private static final Set<String> EVERY_BLOCK = Set.of(
            "hero", "heading", "text", "image", "video", "buttons", "card", "callout", "keypoints", "checklist",
            "stats", "table", "link_cards", "team", "code", "divider",
            "chat", "members", "message_me", "kanban", "feedback", "announcements", "calendar",
            "api_data", "embed");

    /** The rule each invalid fixture breaks, as the key Spring reports. */
    private static final Map<String, String> BROKEN_RULE = Map.ofEntries(
            Map.entry("javascript-link.json", "bad_url"),
            Map.entry("backslash-site-path.json", "bad_url"),
            Map.entry("userinfo-url.json", "bad_url"),
            Map.entry("on-key.json", "bad_key"),
            Map.entry("html-key.json", "bad_key"),
            Map.entry("depth-13.json", "too_deep"),
            Map.entry("bad-id.json", "bad_id"),
            Map.entry("duplicate-id.json", "duplicate_id"),
            Map.entry("wrong-kind.json", "bad_kind"),
            Map.entry("too-many-sections.json", "too_many_sections"),
            Map.entry("snapshot-extra-key.json", "bad_snapshot"),
            Map.entry("body-script.json", "bad_body"),
            Map.entry("loopback-url-on-publish.json", "loopback_url"));

    private final ObjectMapper json = new ObjectMapper();
    private final UiBuilderLimits limits = new UiBuilderLimits();
    private final UiBuilderSpecRules rules = new UiBuilderSpecRules(limits, json);
    private final UiBuilderTestStore store = new UiBuilderTestStore();

    private List<String> keys(JsonNode spec, Mode mode) {
        return rules.validate(spec, mode).stream().map(Issue::key).toList();
    }

    private ObjectNode parse(String text) throws IOException {
        return (ObjectNode) json.readTree(text);
    }

    // ---- shared fixtures ----

    @Test
    void theAllBlocksPagePassesAsADraftAndWhenPublished() {
        ObjectNode page = store.fixture("all-blocks.json");

        assertEquals(List.of(), rules.validate(page, Mode.DRAFT));
        assertEquals(List.of(), rules.validate(page, Mode.PUBLISH));
    }

    @Test
    void theAllBlocksPageUsesEveryBlockTypeAndEveryColumnLayout() {
        ObjectNode page = store.fixture("all-blocks.json");
        Set<String> blockTypes = new TreeSet<>();
        Set<String> layouts = new TreeSet<>();
        Set<String> frames = new TreeSet<>();
        for (JsonNode section : page.get("sections")) {
            frames.add(section.get("type").asText());
            if (section.path("settings").has("layout")) {
                layouts.add(section.get("settings").get("layout").asText());
            }
            for (JsonNode cell : section.get("cells")) {
                for (JsonNode block : cell.get("blocks")) {
                    blockTypes.add(block.get("type").asText());
                }
            }
        }

        assertEquals(new TreeSet<>(EVERY_BLOCK), blockTypes);
        assertEquals(Set.of("stack", "columns"), frames);
        assertEquals(Set.of("halves", "thirds", "sidebar_left", "sidebar_right"), layouts);
        assertEquals(limits.getDepth(), depthOf(page, 1), "the API saved copy reaches the deepest level allowed");
    }

    @Test
    void everyInvalidFixtureFailsForItsOwnRule() throws IOException, URISyntaxException {
        List<String> checked = new ArrayList<>();
        for (Path file : invalidFixtures()) {
            String name = file.getFileName().toString();
            JsonNode spec = json.readTree(file.toFile());
            String rule = BROKEN_RULE.get(name);

            List<String> published = keys(spec, Mode.PUBLISH);
            assertTrue(published.contains(rule), name + " should fail with " + rule + " but gave " + published);
            if (name.endsWith("-on-publish.json")) {
                assertEquals(List.of(), keys(spec, Mode.DRAFT), name + " is a valid draft");
            } else {
                assertTrue(keys(spec, Mode.DRAFT).contains(rule), name + " fails as a draft too");
            }
            checked.add(name);
        }
        assertEquals(new TreeSet<>(BROKEN_RULE.keySet()), new TreeSet<>(checked), "one fixture per rule");
    }

    private static List<Path> invalidFixtures() throws IOException, URISyntaxException {
        URL folder = UiBuilderSpecRulesTest.class.getResource("/ui-builder/invalid");
        try (Stream<Path> files = Files.list(Path.of(folder.toURI()))) {
            return files.filter(file -> file.toString().endsWith(".json")).sorted().toList();
        }
    }

    private static int depthOf(JsonNode node, int level) {
        int deepest = level;
        for (JsonNode child : node) {
            if (child.isContainerNode()) {
                deepest = Math.max(deepest, depthOf(child, level + 1));
            }
        }
        return deepest;
    }

    // ---- the envelope, size and structure ----

    @Test
    void aDocumentThatIsNotAPageIsRejectedBeforeAnythingElse() throws IOException {
        assertEquals(List.of("not_a_page"), keys(json.readTree("[]"), Mode.DRAFT));
        assertEquals(List.of("bad_schema_version"), keys(parse("{\"schemaVersion\": 2, \"kind\": \"ocs-ui-page\"}"), Mode.DRAFT));
        assertEquals(List.of("bad_kind"), keys(parse("{\"schemaVersion\": 1}"), Mode.DRAFT));
    }

    @Test
    void aPageOverTheCharacterLimitIsTooBig() {
        ObjectNode page = store.simplePage("Big");
        String text = "x".repeat(19_000);
        ArrayNode blocks = (ArrayNode) page.at("/sections/0/cells/0/blocks");
        for (int i = 0; i < 11; i++) {
            ObjectNode block = blocks.addObject();
            block.put("id", "b-big" + String.format("%04d", i));
            block.put("type", "code");
            block.putObject("settings").put("code", text);
        }

        assertEquals(List.of("too_big"), keys(page, Mode.DRAFT));
    }

    @Test
    void sectionsCellsAndBlocksHaveLimits() {
        ObjectNode page = store.simplePage("Limits");
        ObjectNode section = (ObjectNode) page.at("/sections/0");
        ArrayNode cells = (ArrayNode) section.get("cells");
        for (int i = 0; i < 3; i++) {
            ArrayNode blocks = cells.addObject().putArray("blocks");
            for (int j = 0; j < 17; j++) {
                ObjectNode block = blocks.addObject();
                block.put("id", "b-c" + i + "x" + String.format("%03d", j));
                block.put("type", "divider");
                block.putObject("settings");
            }
        }

        List<String> problems = keys(page, Mode.DRAFT);
        assertTrue(problems.contains("too_many_cells"), problems.toString());
        assertTrue(problems.contains("too_many_blocks"), problems.toString());
    }

    @Test
    void onlyShownLiveBlocksCountTowardTheLiveLimit() {
        ObjectNode page = store.simplePage("Live");
        ArrayNode blocks = (ArrayNode) page.at("/sections/0/cells/0/blocks");
        for (int i = 0; i < 9; i++) {
            ObjectNode block = blocks.addObject();
            block.put("id", "b-live" + String.format("%03d", i));
            block.put("type", "feedback");
            block.putObject("settings").put("title", "Feedback");
            block.put("hidden", i == 0);
        }
        assertEquals(List.of(), keys(page, Mode.DRAFT), "eight shown, one hidden");

        ((ObjectNode) blocks.get(1)).put("hidden", false);
        assertEquals(List.of("too_many_live"), keys(page, Mode.DRAFT), "nine shown");
    }

    @Test
    void idsAndTypesFollowTheirPatterns() throws IOException {
        ObjectNode page = store.simplePage("Ids");
        ObjectNode block = (ObjectNode) page.at("/sections/0/cells/0/blocks/0");
        block.put("type", "Hero!");
        block.put("hidden", "no");
        ((ObjectNode) page.at("/sections/0")).put("id", "s-ABCDEF");

        List<String> problems = keys(page, Mode.DRAFT);
        assertTrue(problems.containsAll(List.of("bad_type", "bad_hidden", "bad_id")), problems.toString());
        assertEquals(List.of("bad_sections"), keys(parse("{\"schemaVersion\": 1, \"kind\": \"ocs-ui-page\", \"page\": {}}"), Mode.DRAFT));
    }

    // ---- keys and values ----

    @Test
    void eventHandlerMarkupAndPrototypeKeysAreRejectedAtAnyDepthAndCase() {
        for (String key : List.of("onClick", "ONLOAD", "onx", "html", "HTML", "script", "__proto__")) {
            ObjectNode page = store.simplePage("Keys");
            ((ObjectNode) page.at("/sections/0/cells/0/blocks/0/settings")).putObject("deeper").put(key, "x");
            assertEquals(List.of("bad_key"), keys(page, Mode.DRAFT), key);
        }
        ObjectNode page = store.simplePage("Keys");
        ((ObjectNode) page.get("page")).put("onerror", "x");
        assertEquals(List.of(new Issue("page", "onerror", "bad_key")), rules.validate(page, Mode.DRAFT));
    }

    @Test
    void anyStringOverTwentyThousandCharactersIsTooLong() {
        ObjectNode page = store.simplePage("Long");
        ((ObjectNode) page.at("/sections/0/cells/0/blocks/0/settings")).put("lead", "x".repeat(20_001));

        assertEquals(List.of(new Issue("b-hero0001", "lead", "too_long")), rules.validate(page, Mode.DRAFT));
    }

    @Test
    void linkAndImageAddressesFollowTheUrlRules() {
        List<String> allowed = List.of("", "  https://example.com/a?b=c#d  ", "HTTPS://Example.com", "/about",
                "/images/a.png?x=1", "https://example.com/search?q=a|b", "https://例え.jp/パス",
                "http://localhost:8587/api/jokes", "http://127.0.0.1/x", "HTTP://LOCALHOST");
        for (String value : allowed) {
            assertNull(UiBuilderSpecRules.urlIssue(TextNode.valueOf(value), false, Mode.DRAFT), value);
        }
        List<String> refused = List.of("javascript:alert(1)", " JavaScript:alert(1)", "data:text/html,x",
                "vbscript:x", "file:///etc/passwd", "//evil.example.com", "/\\evil.example.com", "\\\\evil",
                "https://user:pass@example.com", "https://example.com@evil.example.com", "https:///path",
                "https://exa mple.com", "https://example.com/‮", "https://example.com/​", "http://example.com",
                "http://localhost.evil.example.com", "http://localhost@evil.example.com", "ftp://example.com",
                "example.com", "mailto:a@example.com");
        for (String value : refused) {
            assertEquals("bad_url", UiBuilderSpecRules.urlIssue(TextNode.valueOf(value), false, Mode.DRAFT), value);
        }
        assertEquals("bad_url", UiBuilderSpecRules.urlIssue(json.getNodeFactory().numberNode(5), false, Mode.DRAFT));
        assertNull(UiBuilderSpecRules.urlIssue(null, false, Mode.DRAFT));
    }

    @Test
    void mailtoIsOnlyForTheUrlOfAButtonLink() {
        assertNull(UiBuilderSpecRules.urlIssue(TextNode.valueOf("mailto:team@example.com"), true, Mode.PUBLISH));
        assertEquals("bad_url", UiBuilderSpecRules.urlIssue(TextNode.valueOf("mailto:<script>"), true, Mode.DRAFT));

        ObjectNode page = store.simplePage("Mail");
        ObjectNode settings = (ObjectNode) page.at("/sections/0/cells/0/blocks/0/settings");
        settings.putArray("links").addObject().put("label", "Mail").put("url", "mailto:team@example.com");
        assertEquals(List.of(), keys(page, Mode.DRAFT));

        settings.putArray("items").addObject().put("name", "Ada").put("url", "mailto:ada@example.com");
        assertEquals(List.of(new Issue("b-hero0001", "items", "bad_url")), rules.validate(page, Mode.DRAFT));
    }

    @Test
    void aPublishedPageCanNotPointAtAComputersOwnAddress() {
        for (String value : List.of("http://localhost:8587/api", "http://127.0.0.1:5000", "https://localhost/x",
                "https://127.0.0.2/x", "https://[::1]/x", "https://app.localhost/x", "https://0.0.0.0/")) {
            assertEquals(UiBuilderSpecRules.LOOPBACK_URL,
                    UiBuilderSpecRules.urlIssue(TextNode.valueOf(value), false, Mode.PUBLISH), value);
        }
        assertNull(UiBuilderSpecRules.urlIssue(TextNode.valueOf("https://flask.opencodingsociety.com/api"), false, Mode.PUBLISH));
    }

    @Test
    void richTextThatRunsCodeIsRejected() {
        List<String> unsafe = List.of("<script>alert(1)</script>", "<SCRIPT src=x>", "< script>", "<scr\nipt>",
                "<iframe src=x>", "<object data=x>", "<embed src=x>", "<a href=\"javascript:alert(1)\">x</a>",
                "<a href=\"java\tscript:alert(1)\">x</a>", "<a href=\"java&#x09;script:alert(1)\">x</a>",
                "<a href=\"&#106;avascript:alert(1)\">x</a>", "<a href=\"javascript&colon;alert(1)\">x</a>",
                "<img src=x onerror=alert(1)>", "<img src=x\nonerror=alert(1)>", "<svg/onload=alert(1)>",
                "<img src=\"x\"onerror=alert(1)>", "<b ONMOUSEOVER = 'x'>", "<p>unclosed <script");
        for (String body : unsafe) {
            assertTrue(UiBuilderSpecRules.unsafeBody(body), body);
        }
        List<String> safe = List.of("<b>Bold</b> and <i>italic</i><br><ul><li>One</li></ul>",
                "<a href=\"https://example.com\">a link</a>", "Use the javascript: scheme? Never.",
                "2 < 3 and 5 > 4", "&lt;script&gt; is shown as text", "<span class=\"rt-size-lg\">Big</span>",
                "<b>Weekly on Mondays</b>");
        for (String body : safe) {
            assertFalse(UiBuilderSpecRules.unsafeBody(body), body);
        }
    }

    @Test
    void apiPathsAndSavedCopiesAreChecked() {
        ObjectNode page = store.fixture("all-blocks.json");
        ObjectNode source = (ObjectNode) page.at("/sections/4/cells/0/blocks/0/settings/source");
        source.put("title_path", "a b");
        assertEquals(List.of(new Issue("b-apidata01", "source", "bad_path")), rules.validate(page, Mode.DRAFT));

        source.put("title_path", "items[0].name");
        ArrayNode items = (ArrayNode) source.at("/snapshot/items");
        for (int i = 0; i < 48; i++) {
            items.add(items.get(0).deepCopy());
        }
        assertEquals(List.of(new Issue("b-apidata01", "source", "bad_snapshot")), rules.validate(page, Mode.DRAFT));

        source.put("snapshot", "not an object");
        assertEquals(List.of(new Issue("b-apidata01", "source", "bad_snapshot")), rules.validate(page, Mode.DRAFT));
        source.putNull("snapshot");
        assertEquals(List.of(), rules.validate(page, Mode.DRAFT));
    }

    @Test
    void aGroupFieldIsEmptyOrAGroupId() {
        ObjectNode page = store.fixture("all-blocks.json");
        ObjectNode chat = (ObjectNode) page.at("/sections/3/cells/1/blocks/0/settings");
        chat.putNull("group");
        assertEquals(List.of(), rules.validate(page, Mode.DRAFT));

        chat.putObject("group").put("id", "42").put("name", "Binary Beasts");
        assertEquals(List.of(new Issue("b-chat01", "group", "group_not_allowed")), rules.validate(page, Mode.DRAFT));
    }

    // ---- what the service builds on ----

    @Test
    void stripHiddenRemovesHiddenSectionsAndBlocksFromACopy() {
        ObjectNode page = store.fixture("all-blocks.json");
        ((ObjectNode) page.at("/sections/1")).put("hidden", true);
        ((ObjectNode) page.at("/sections/0/cells/0/blocks/2")).put("hidden", true);

        ObjectNode published = rules.stripHidden(page);

        assertEquals(4, published.get("sections").size());
        assertEquals(7, published.at("/sections/0/cells/0/blocks").size());
        assertFalse(published.toString().contains("b-text01"));
        assertFalse(published.toString().contains("s-halves01"));
        assertEquals(5, page.get("sections").size(), "the draft keeps everything");
    }

    @Test
    void groupRefsAreFoundAndRenamedToSpringsNames() {
        ObjectNode page = store.fixture("all-blocks.json");

        assertEquals(List.of(new GroupRef("b-members01", 42), new GroupRef("b-chat01", 42)), rules.groupRefs(page));
        rules.rewriteGroupNames(page, Map.of(42L, "Binary Beasts (P3)"));
        assertEquals("Binary Beasts (P3)", page.at("/sections/3/cells/1/blocks/0/settings/group/name").asText());
        assertTrue(rules.membersBlockSettings(page, "b-members01").isPresent());
        assertTrue(rules.membersBlockSettings(page, "b-chat01").isEmpty(), "a chat block is not a members block");
    }

    @Test
    void pageSettingsHaveTheirOwnRules() {
        Set<String> courses = Set.of("csa", "csp", "csh", "csse");

        assertEquals(List.of(), rules.pageFieldIssues(new PageFields("Flood Watch", "", "", "csp", "/a.png"), courses));
        assertEquals(List.of(new Issue("page", "title", "required")),
                rules.pageFieldIssues(new PageFields("  ", null, null, "", null), courses));
        assertEquals(List.of(new Issue("page", "title", "one_line")),
                rules.pageFieldIssues(new PageFields("Two\nlines", null, null, "", null), courses));
        List<Issue> issues = rules.pageFieldIssues(new PageFields("x".repeat(121), "d".repeat(301), "b".repeat(121),
                "CSP", "http://localhost/x.png"), courses);
        assertEquals(List.of(new Issue("page", "title", "too_long"), new Issue("page", "description", "too_long"),
                new Issue("page", "byline", "too_long"), new Issue("page", "course", "bad_course"),
                new Issue("page", "image", "bad_image")), issues);
    }

    @Test
    void theGalleryReadsPageSettingsWithoutTheRestOfTheSpec() throws IOException {
        String sectionsFirst = "{\"sections\": [{\"cells\": [{\"blocks\": [{\"page\": {\"title\": \"wrong\"}}]}]}],"
                + " \"page\": {\"title\": \"Flood Watch\", \"description\": \"Levels\", \"byline\": \"Team\","
                + " \"course\": \"csp\", \"image\": \"/a.png\"}}";

        assertEquals(new PageFields("Flood Watch", "Levels", "Team", "csp", "/a.png"), rules.pageFieldsOf(sectionsFirst));
        assertEquals(new PageFields(null, null, null, null, null), rules.pageFieldsOf("{}"));
        assertEquals(new PageFields(null, null, null, null, null), rules.pageFieldsOf("not json"));
        assertEquals(rules.pageFieldsOf(json.readTree(sectionsFirst)), rules.pageFieldsOf(sectionsFirst));
    }

    @Test
    void slugsFollowThePatternAndSkipReservedWords() {
        assertNull(rules.slugIssue("flood-watch"));
        assertNull(rules.slugIssue("abc"));
        assertEquals("invalid", rules.slugIssue("ab"));
        assertEquals("invalid", rules.slugIssue("Flood-Watch"));
        assertEquals("invalid", rules.slugIssue("-flood"));
        assertEquals("invalid", rules.slugIssue("a".repeat(65)));
        assertEquals("invalid", rules.slugIssue(null));
        assertEquals("reserved", rules.slugIssue("admin"));
        assertEquals("reserved", rules.slugIssue("gallery"));
    }
}
