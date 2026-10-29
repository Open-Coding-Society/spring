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
            Map.entry("userinfo-empty-host-link.json", "bad_url"),
            Map.entry("dot-segment-link.json", "bad_url"),
            Map.entry("dot-segment-encoded-link.json", "bad_url"),
            Map.entry("https-typo-image.json", "bad_url"),
            Map.entry("on-key.json", "bad_key"),
            Map.entry("html-key.json", "bad_key"),
            Map.entry("depth-13.json", "too_deep"),
            Map.entry("bad-id.json", "bad_id"),
            Map.entry("duplicate-id.json", "duplicate_id"),
            Map.entry("wrong-kind.json", "bad_kind"),
            Map.entry("too-many-sections.json", "too_many_sections"),
            Map.entry("snapshot-extra-key.json", "bad_snapshot"),
            Map.entry("body-script.json", "bad_body"),
            Map.entry("rich-text-quoted-gt.json", "bad_body"),
            Map.entry("rich-text-tel-link.json", "bad_body"),
            Map.entry("rich-text-noscript.json", "bad_body"),
            Map.entry("rich-text-unknown-attribute.json", "bad_body"),
            Map.entry("rich-text-padded-reference.json", "bad_body"),
            Map.entry("long-gallery-image.json", "too_long"),
            Map.entry("blank-long-gallery-image.json", "too_long"),
            Map.entry("loopback-url-on-publish.json", "loopback_url"),
            Map.entry("https-loopback-link-on-publish.json", "loopback_url"),
            Map.entry("https-loopback-image-on-publish.json", "loopback_url"),
            Map.entry("rich-text-loopback-link-on-publish.json", "loopback_url"),
            Map.entry("rich-text-padded-loopback-on-publish.json", "loopback_url"),
            Map.entry("ipv6-mapped-loopback-link-on-publish.json", "loopback_url"));

    /** The shared valid fixtures, beside invalid/ (Pages keeps them in specs/valid/). */
    private static final Set<String> VALID = Set.of(
            "all-blocks.json", "rich-text-composer-output.json", "snapshot-mailto-link.json");

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

    /** A page whose one section holds these blocks, written as JSON (the shape of the shared fixtures). */
    private ObjectNode pageWith(String blocksJson) throws IOException {
        return parse("""
                {"schemaVersion": 1, "kind": "ocs-ui-page",
                 "page": {"title": "Inline", "description": "", "byline": "", "course": "csp", "width": "contained", "image": ""},
                 "sections": [{"id": "s-inline01", "type": "stack", "settings": {}, "hidden": false,
                   "cells": [{"blocks": [BLOCKS]}]}]}
                """.replace("BLOCKS", blocksJson));
    }

    private ObjectNode pageWithButton(String url) throws IOException {
        return pageWith("""
                {"id": "b-button01", "type": "buttons", "hidden": false,
                 "settings": {"links": [{"label": "Sign in", "url": "URL"}], "emphasize_first": true}}
                """.replace("URL", url));
    }

    /** A page whose one block is a text block holding this rich text (set as a value, so it needs no JSON escaping). */
    private ObjectNode pageWithText(String body) throws IOException {
        ObjectNode page = pageWith("""
                {"id": "b-text0001", "type": "text", "hidden": false, "settings": {"body": ""}}
                """);
        ((ObjectNode) page.at("/sections/0/cells/0/blocks/0/settings")).put("body", body);
        return page;
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
    void everyValidFixturePassesAsADraftAndWhenPublished() throws IOException, URISyntaxException {
        List<String> checked = new ArrayList<>();
        for (Path file : fixtures("/ui-builder")) {
            String name = file.getFileName().toString();
            JsonNode spec = json.readTree(file.toFile());

            assertEquals(List.of(), rules.validate(spec, Mode.DRAFT), name);
            assertEquals(List.of(), rules.validate(spec, Mode.PUBLISH), name + " published");
            checked.add(name);
        }
        assertEquals(new TreeSet<>(VALID), new TreeSet<>(checked), "every shared valid fixture is here");
    }

    @Test
    void everyInvalidFixtureFailsForItsOwnRule() throws IOException, URISyntaxException {
        List<String> checked = new ArrayList<>();
        for (Path file : fixtures("/ui-builder/invalid")) {
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

    /** The .json files directly in a classpath folder (not those in its subfolders). */
    private static List<Path> fixtures(String classpathFolder) throws IOException, URISyntaxException {
        URL folder = UiBuilderSpecRulesTest.class.getResource(classpathFolder);
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
                "https://exa mple.com", "https://example.com/\u202e", "https://example.com/\u200b", "http://example.com",
                "http://localhost.evil.example.com", "http://localhost@evil.example.com", "ftp://example.com",
                "example.com", "mailto:a@example.com");
        for (String value : refused) {
            assertEquals("bad_url", UiBuilderSpecRules.urlIssue(TextNode.valueOf(value), false, Mode.DRAFT), value);
        }
        assertEquals("bad_url", UiBuilderSpecRules.urlIssue(json.getNodeFactory().numberNode(5), false, Mode.DRAFT));
        assertNull(UiBuilderSpecRules.urlIssue(null, false, Mode.DRAFT));
    }

    @Test
    void aSitePathWithADotSegmentIsRefusedEvenEncoded() throws IOException {
        for (String url : List.of("/..//evil.com/x", "/.%2e//evil.com/x")) {
            assertEquals(List.of(new Issue("b-button01", "links", "bad_url")), rules.validate(pageWithButton(url), Mode.DRAFT), url);
        }
        for (String value : List.of("/%2E%2E//evil.com", "/a/../..//evil.com/x", "/.//evil.com", "/a/.", "/a/%2e?x=1",
                "/a/..#top")) {
            assertEquals("bad_url", UiBuilderSpecRules.urlIssue(TextNode.valueOf(value), false, Mode.DRAFT), value);
        }
        for (String value : List.of("/search?q=/../x", "/a.b/c", "/.well-known/x", "/...", "/a/b#/../c")) {
            assertNull(UiBuilderSpecRules.urlIssue(TextNode.valueOf(value), false, Mode.DRAFT), value);
        }
        assertEquals(List.of(new Issue("page", "image", "bad_image")), rules.pageFieldIssues(
                new PageFields("Title", null, null, "", "/..//evil.com/logo.png"), Set.of()));
    }

    @Test
    void addressesABrowserWouldRepairAreRefusedAsSpringReadsThem() throws IOException {
        ObjectNode image = pageWith("""
                {"id": "b-image01", "type": "image", "hidden": false,
                 "settings": {"src": "https:/team.github.io/logo.png", "alt": "Our logo"}}
                """);
        assertEquals(List.of(new Issue("b-image01", "src", "bad_url")), rules.validate(image, Mode.DRAFT));
        assertEquals(List.of(new Issue("b-button01", "links", "bad_url")),
                rules.validate(pageWithButton("https://@example.com/"), Mode.DRAFT));
        for (String value : List.of("https:team.github.io", "https:///example.com", "http:/localhost:5000/api",
                "http://127.1:5000/api", "http://localhost./api")) {
            assertEquals("bad_url", UiBuilderSpecRules.urlIssue(TextNode.valueOf(value), false, Mode.DRAFT), value);
        }
    }

    @Test
    void anApiSavedCopyMayLinkAnItemToAMailtoAddress() throws IOException {
        String block = """
                {"id": "b-apidata02", "type": "api_data", "hidden": false,
                 "settings": {"title": "Our team", "view": "cards", "limit": 12,
                   "source": {"url": "https://flood-watch.opencodingsociety.com/api/team", "list_path": "people",
                     "title_path": "name", "link_path": "contact",
                     "snapshot": {"items": [{"title": "Ada", "link": "LINK", "image": "IMAGE"}],
                       "fetched_at": 1793206800000, "from_loopback": false}}}}
                """;
        ObjectNode mailto = pageWith(block.replace("LINK", "mailto:ada@example.com").replace("IMAGE", ""));
        assertEquals(List.of(), rules.validate(mailto, Mode.DRAFT));
        assertEquals(List.of(), rules.validate(mailto, Mode.PUBLISH));

        ObjectNode mailtoImage = pageWith(block.replace("LINK", "").replace("IMAGE", "mailto:ada@example.com"));
        assertEquals(List.of(new Issue("b-apidata02", "source", "bad_url")), rules.validate(mailtoImage, Mode.DRAFT));
        ObjectNode notACopy = pageWith("""
                {"id": "b-card0001", "type": "card", "hidden": false,
                 "settings": {"title": "Mail", "items": [{"title": "Ada", "link": "mailto:ada@example.com"}]}}
                """);
        assertEquals(List.of(new Issue("b-card0001", "items", "bad_url")), rules.validate(notACopy, Mode.DRAFT),
                "only a saved copy's items");
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
    void theUnspecifiedAddressAndLoopbackMappedIntoIpv6AreTheComputersOwnToo() throws IOException {
        // Read as typed, in any case: [::], and 127.x.x.x mapped into IPv6, with its four numbers or in
        // hex as the URL parser writes it (https://[::ffff:127.0.0.1]/ goes to [::ffff:7f00:1]).
        String onComputer = UiBuilderSpecRules.LOOPBACK_URL;
        for (String address : List.of("https://[::]/", "https://[::]:8443/demo", "https://[::ffff:127.0.0.1]:8443/",
                "https://[::ffff:127.10.20.30]/x", "https://[::ffff:7f00:1]/", "HTTPS://[::FFFF:7F0A:141E]/x")) {
            ObjectNode page = pageWith("""
                    {"id": "b-button01", "type": "buttons", "hidden": false,
                     "settings": {"links": [{"label": "Try our app", "url": "ADDRESS"}], "emphasize_first": true}},
                    {"id": "b-image01", "type": "image", "hidden": false, "settings": {"src": "ADDRESS", "alt": "Our app"}},
                    {"id": "b-apidata02", "type": "api_data", "hidden": false,
                     "settings": {"title": "Our team", "view": "cards", "limit": 12,
                       "source": {"url": "https://flood-watch.opencodingsociety.com/api/team", "list_path": "people",
                         "title_path": "name", "link_path": "contact",
                         "snapshot": {"items": [{"title": "Ada", "link": "ADDRESS", "image": "ADDRESS"}],
                           "fetched_at": 1793206800000, "from_loopback": false}}}},
                    {"id": "b-text0001", "type": "text", "hidden": false,
                     "settings": {"body": "Try <a href=\\"ADDRESS\\">our app</a> before class."}},
                    {"id": "b-text0002", "type": "text", "hidden": false,
                     "settings": {"body": "<img src=\\"ADDRESS\\" alt=\\"Our app\\">"}}
                    """.replace("ADDRESS", address));

            assertEquals(List.of(), rules.validate(page, Mode.DRAFT), address);
            assertEquals(List.of(new Issue("b-button01", "links", onComputer), new Issue("b-image01", "src", onComputer),
                    new Issue("b-apidata02", "source", onComputer), new Issue("b-apidata02", "source", onComputer),
                    new Issue("b-text0001", "body", onComputer), new Issue("b-text0002", "body", onComputer)),
                    rules.validate(page, Mode.PUBLISH), address + " published");
            assertEquals(List.of(new Issue("page", "image", "bad_image")),
                    rules.pageFieldIssues(new PageFields("Our app", null, null, "", address), Set.of()), "gallery picture " + address);
        }
        // Other IPv6 addresses are not the student's computer, so a published page may link to them.
        for (String value : List.of("https://[::2]/", "https://[::ffff:128.0.0.1]/", "https://[::ffff:8f00:1]/",
                "https://[::fffe:7f00:1]/", "https://[::ffff:7f00:1:2]/", "https://[2001:db8::7f00:1]/")) {
            assertNull(UiBuilderSpecRules.urlIssue(TextNode.valueOf(value), false, Mode.PUBLISH), value);
        }
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
    void aGreaterThanInsideAQuotedAttributeCantHideTheRestOfATag() throws IOException {
        ObjectNode page = pageWith("""
                {"id": "b-text0001", "type": "text", "hidden": false,
                 "settings": {"body": "<a title=\\">\\" href=\\"javascript:alert(1)\\" onclick=\\"alert(1)\\">x</a>"}}
                """);
        assertEquals(List.of(new Issue("b-text0001", "body", "bad_body")), rules.validate(page, Mode.DRAFT));

        List<String> unsafe = List.of("<a x\"y=1 onclick=alert(1)>hi</a>", "<a title='>' href='data:text/html,x'>x</a>",
                "<a title=\">\" href=\"vbscript:msgbox(1)\">x</a>", "<a title=\">\" href=\" JaVa&#x0A;Script:x\">x</a>",
                "<math><mi title='>' xlink:href=\"javascript:alert(1)\">x</mi></math>",
                "<svg><style><img title='>' src=x onerror=alert(1)>",
                "<noscript><p title=\"</noscript><img title='>' src=x onerror=alert(1)>\"></noscript>",
                "<b title=\">\"><script>alert(1)</script></b>");
        for (String body : unsafe) {
            assertTrue(UiBuilderSpecRules.unsafeBody(body), body);
        }
        // What the rich-text editor writes: http links too, with target and rel.
        List<String> safe = List.of("<a href=\"http://example.com\" target=\"_blank\" rel=\"noopener noreferrer\">x</a>",
                "<a href=\"mailto:ada@example.com\">Mail Ada</a>", "<a title=\"2 > 1\" href=\"https://example.com\">x</a>",
                "<span class=\"rt-font-mono\">code</span> and <s>old</s>");
        for (String body : safe) {
            assertFalse(UiBuilderSpecRules.unsafeBody(body), body);
        }
    }

    @Test
    void zerosInACharacterReferenceCantHideCodeFromTheCheckOfEachTag() {
        // Browsers and jsoup read every digit, so "&#0000000115;" is "s" like "&#115;". The check of each
        // run from < to > reads it so too, also where jsoup keeps a reference as typed: after a "<" that
        // starts no tag, or in an attribute's name. The editor's page-rules.js makes the same check.
        for (String s : List.of("&#115;", "&#0000000115;", "&#x73;", "&#x0000073;")) {
            String body = "<p>Use <" + s + "cript> tags</p>";
            assertTrue(UiBuilderSpecRules.unsafeBody(body), body);
        }
        for (String o : List.of("&#111;", "&#00000000111;", "&#x6f;", "&#x000006F;")) {
            String body = "<img src=\"/a.png\" alt=\"\" " + o + "nerror=alert(1)>";
            assertTrue(UiBuilderSpecRules.unsafeBody(body), body);
        }
    }

    @Test
    void richTextMayLinkToTheTeamsComputerInADraftButNotWhenPublished() throws IOException {
        for (String address : List.of("https://localhost:8443/", "https://127.0.0.1:4100/", "http://localhost:4000/")) {
            ObjectNode page = pageWithText("Try <a href=\"" + address + "\">our app</a> before class.");

            assertEquals(List.of(), rules.validate(page, Mode.DRAFT), address);
            assertEquals(List.of(new Issue("b-text0001", "body", UiBuilderSpecRules.LOOPBACK_URL)),
                    rules.validate(page, Mode.PUBLISH), address);
        }
    }

    @Test
    void aRichTextLinkOrPictureIsOnTheComputerWhenTheSameUrlValueWouldBe() throws IOException {
        Issue onComputer = new Issue("b-text0001", "body", UiBuilderSpecRules.LOOPBACK_URL);
        // Each value decoded, then read as typed like a url value.
        for (String address : List.of("HTTPS://LOCALHOST./x", "https://app.localhost:3000/", "https://[::1]:8443/",
                "https://0.0.0.0/", " https://127.0.0.2/x ", "HTTP://127.0.0.1:4100/api", "https://localhost&#47;x")) {
            for (String body : List.of("<a href=\"" + address + "\">x</a>", "<img src=\"" + address + "\" alt=\"x\">")) {
                assertEquals(List.of(onComputer), rules.validate(pageWithText(body), Mode.PUBLISH), body);
            }
        }
        // Another host, a site path, mail, any other http, and addresses in attributes that aren't links or pictures.
        for (String body : List.of("<a href=\"https://localhost.example.com/\">x</a>", "<a href=\"/about\">x</a>",
                "<a href=\"mailto:ada@example.com\">x</a>", "<a href=\"http://example.com/\">x</a>",
                "<a title=\"https://localhost/\" href=\"https://example.com/\">x</a>",
                "<a title='href=\"https://localhost/\"' href=\"https://example.com/\">x</a>")) {
            assertEquals(List.of(), rules.validate(pageWithText(body), Mode.PUBLISH), body);
        }
        // One problem per body, as for a url value: code to run comes first.
        assertEquals(List.of(new Issue("b-text0001", "body", "bad_body")),
                rules.validate(pageWithText("<a href=\"https://localhost/\" onclick=\"go()\">x</a>"), Mode.PUBLISH));
    }

    @Test
    void aLinkToTheComputerCantHideFromEitherReadingOfRichText() throws IOException {
        // The parsed body finds this href; the run from "<" to the next ">" stops at the quoted one.
        String quotedGreaterThan = "<a title=\">\" href=\"https://localhost:8443/\">our app</a>";
        // jsoup drops an <a> inside <select>, but Chrome keeps it and rich-text.js shows it as a link;
        // parsed on its own, the run from "<" to ">" is a link again.
        String insideSelect = "<select><a href=\"https://localhost:8443/\">our app</a></select>";
        for (String body : List.of(quotedGreaterThan, insideSelect)) {
            ObjectNode page = pageWithText(body);

            assertEquals(List.of(), rules.validate(page, Mode.DRAFT), body);
            assertEquals(List.of(new Issue("b-text0001", "body", UiBuilderSpecRules.LOOPBACK_URL)),
                    rules.validate(page, Mode.PUBLISH), body);
        }
    }

    @Test
    void anAddressKeepsTheSeparatorsThatJavaScriptsTrimKeeps() throws IOException {
        // Java counts U+001C to U+001F as whitespace; JavaScript's trim, which the editor reads addresses
        // with, keeps them. Kept, one makes a url value bad_url and an href no link at all (the page
        // renderer shows it as text), so the href isn't a link to the student's computer either.
        for (char separator = 0x1c; separator <= 0x1f; separator++) {
            String reference = String.format("&#x%x;", (int) separator);
            for (String value : List.of(separator + "https://example.com/", "https://example.com/" + separator,
                    separator + "https://localhost/", "https://localhost/" + separator)) {
                assertEquals("bad_url", UiBuilderSpecRules.urlIssue(TextNode.valueOf(value), false, Mode.DRAFT), value);
                assertEquals("bad_url", UiBuilderSpecRules.urlIssue(TextNode.valueOf(value), false, Mode.PUBLISH), value);
            }
            for (String href : List.of(separator + "https://localhost/", "https://localhost/" + separator,
                    reference + "https://localhost/", "https://localhost/" + reference)) {
                ObjectNode page = pageWithText("Try <a href=\"" + href + "\">our app</a>.");
                assertEquals(List.of(), rules.validate(page, Mode.PUBLISH), href);
            }
        }
        // A tab or a no-break space at either end is trimmed on both sides, so the link is to the computer.
        for (String href : List.of("\thttps://localhost/", "https://localhost/&#xa0;")) {
            ObjectNode page = pageWithText("Try <a href=\"" + href + "\">our app</a>.");
            assertEquals(List.of(new Issue("b-text0001", "body", UiBuilderSpecRules.LOOPBACK_URL)),
                    rules.validate(page, Mode.PUBLISH), href);
        }
    }

    @Test
    void aGalleryPictureOverFiveHundredCharactersIsTooLong() throws IOException {
        String tooLong = "https://cdn.example.com/" + "a".repeat(477);
        assertEquals(501, tooLong.length());
        ObjectNode page = pageWith("""
                {"id": "b-hero0001", "type": "hero", "hidden": false, "settings": {"title": "Long picture"}}
                """);
        ((ObjectNode) page.get("page")).put("image", tooLong);

        assertEquals(List.of(new Issue("page", "image", "too_long")), rules.validate(page, Mode.DRAFT));
        assertEquals(List.of(new Issue("page", "image", "too_long")),
                rules.pageFieldIssues(rules.pageFieldsOf(page), Set.of("csp")));

        ((ObjectNode) page.get("page")).put("image", tooLong.substring(0, 500));
        assertEquals(List.of(), rules.validate(page, Mode.DRAFT));
        assertEquals(List.of(), rules.pageFieldIssues(rules.pageFieldsOf(page), Set.of("csp")));
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
