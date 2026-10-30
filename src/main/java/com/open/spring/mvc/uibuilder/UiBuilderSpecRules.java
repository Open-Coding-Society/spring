package com.open.spring.mvc.uibuilder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

/**
 * The checks Spring makes on a page document before storing it (the same rules the editor's
 * validator applies, so a page passes both or neither). Pure: no database, no request; the limits
 * come from UiBuilderLimits. Spring never renders a spec, so these checks are a second line behind
 * the page renderer's own allow-lists: they keep scripts, event handlers and unsafe links out of
 * what other people's browsers are sent.
 *
 * The key convention makes this possible without the block registry: URLs live only under keys
 * named url, src, image or link; rich text only under body; API field paths under keys ending _path.
 */
@Component
public class UiBuilderSpecRules {

    /** DRAFT lets a page read an API on the student's own computer; a published page can't. */
    public enum Mode { DRAFT, PUBLISH }

    /** One problem: the section or block it is in ("page" for page settings), the field, and a key the editor shows as text. */
    public record Issue(String nodeId, String field, String key) {}

    /** A chat or members block's group. */
    public record GroupRef(String nodeId, long groupId) {}

    /** The page settings kept in columns for lists and the gallery. */
    public record PageFields(String title, String description, String byline, String course, String image) {}

    public static final String KIND = "ocs-ui-page";
    public static final String LOOPBACK_URL = "loopback_url";
    /** Blocks that mount something live in the viewer; a page may show at most limits.liveBlocks of them. */
    public static final Set<String> LIVE_TYPES = Set.of(
            "chat", "members", "message_me", "kanban", "feedback", "announcements", "calendar", "api_data");

    static final int TITLE_MAX = 120;
    static final int DESCRIPTION_MAX = 300;
    static final int BYLINE_MAX = 120;
    /** The gallery picture's column is varchar(500): a longer address is refused, never cut. */
    static final int IMAGE_MAX = 500;

    private static final Pattern ID = Pattern.compile("^[sb]-[a-z0-9]{6,12}$");
    private static final Pattern TYPE = Pattern.compile("^[a-z][a-z0-9_]{1,31}$");
    private static final Pattern PATH = Pattern.compile("^[A-Za-z0-9_.\\[\\]-]{0,120}$");
    private static final Set<String> URL_KEYS = Set.of("url", "src", "image", "link");
    private static final Set<String> SNAPSHOT_KEYS = Set.of("items", "fetched_at", "from_loopback");
    private static final Set<String> SNAPSHOT_ITEM_KEYS = Set.of("title", "text", "image", "link", "value", "label");

    // Characters browsers drop or rewrite inside a URL (whitespace, controls, zero-width and
    // direction marks, backslash), which lets a link point somewhere other than it looks.
    // The same set as UNSAFE in the editor's url-rules.js.
    private static final Pattern UNSAFE_URL_CHAR = Pattern.compile(
            "[\\s\\u0000-\\u001f\\u007f-\\u009f\\u00a0\\u00ad\\u061c\\u1680\\u180e\\u2000-\\u200f"
                    + "\\u2028-\\u202f\\u205f-\\u2064\\u2066-\\u2069\\u3000\\ufeff\\\\]");
    private static final Pattern MAILTO = Pattern.compile("^mailto:[^<>\"'`]+$", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTTP_LOOPBACK = Pattern.compile(
            "^http://(?:localhost|127\\.0\\.0\\.1)(?::\\d{1,5})?(?:[/?#].*)?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(?:\\.\\d{1,3}){3}$");
    // 127.x.x.x mapped into IPv6, typed with its four numbers or in hex, as the URL parser writes it
    // (https://[::ffff:127.0.0.1]/ goes to [::ffff:7f00:1]): the student's own computer too.
    private static final Pattern MAPPED_LOOPBACK = Pattern.compile(
            "^\\[::ffff:(?:127(?:\\.\\d{1,3}){3}|7f[0-9a-f]{2}:[0-9a-f]{1,4})\\]$");
    // A "." or ".." path segment, also written with %2e: browsers resolve it away, so "/..//evil.com"
    // becomes the protocol-relative "//evil.com". Checked in the path only, so "/search?q=/../x" is fine.
    private static final Pattern DOT_SEGMENT = Pattern.compile("(?:^|/)(?:\\.|%2e){1,2}(?=/|$)", Pattern.CASE_INSENSITIVE);

    // A tag runs from < to the next > (or the end of the text, for an unclosed one).
    private static final Pattern TAG = Pattern.compile("<[^>]*>?");
    // Every digit, as browsers and jsoup read a reference: "&#0000000115;" is "s", not a NUL and the
    // text "115;". The editor's page-rules.js reads tags with the same pattern.
    private static final Pattern CHAR_REFERENCE = Pattern.compile("&#(?:[xX]([0-9a-fA-F]+)|([0-9]+));?");
    private static final Pattern NOT_PRINTED = Pattern.compile(
            "[\\s\\u0000-\\u001f\\u007f-\\u009f\\u00a0\\u00ad\\u200b-\\u200f\\u2028-\\u202f\\u2060-\\u2064\\ufeff]+");
    private static final Pattern EVENT_HANDLER = Pattern.compile("[\\s/\"'`]on\\w+\\s*=", Pattern.CASE_INSENSITIVE);
    private static final List<String> FORBIDDEN_IN_TAG = List.of("<script", "<iframe", "<object", "<embed", "javascript:");
    // Rich text parsed as a browser does (jsoup), so a ">" inside a quoted attribute can't end a tag early.
    // The last four are parsed differently by browsers than by jsoup (scripting on, SVG and MathML
    // content), so what is inside them can't be checked; rich text never needs them.
    private static final Set<String> CODE_ELEMENTS = Set.of("script", "iframe", "object", "embed",
            "noscript", "template", "svg", "math");
    private static final Set<String> URL_ATTRIBUTES = Set.of("href", "src", "srcset", "action", "formaction",
            "xlink:href", "data", "poster", "background", "cite", "codebase");
    // The schemes the rich-text editor (assets/js/chat/rich-text.js) writes into links.
    private static final Set<String> RICH_LINK_SCHEMES = Set.of("http", "https", "mailto");
    // The rich-text attributes that link to (href) or show (src) an address: a published page
    // can't point them at the student's own computer, the same rule as a url or src value.
    private static final Set<String> LINK_ATTRIBUTES = Set.of("href", "src");
    private static final Pattern SCHEME = Pattern.compile("^([a-z][a-z0-9+.-]*):");

    private final UiBuilderLimits limits;
    private final ObjectMapper objectMapper;
    private final Pattern slugPattern;

    public UiBuilderSpecRules(UiBuilderLimits limits, ObjectMapper objectMapper) {
        this.limits = limits;
        this.objectMapper = objectMapper;
        this.slugPattern = Pattern.compile(limits.getSlugPattern());
    }

    // ---- the whole document ----

    /** Every problem with a spec; empty when Spring may store it. */
    public List<Issue> validate(JsonNode spec, Mode mode) {
        List<Issue> issues = new ArrayList<>();
        if (spec == null || !spec.isObject()) {
            issues.add(new Issue(null, null, "not_a_page"));
            return issues;
        }
        JsonNode version = spec.get("schemaVersion");
        if (version == null || !version.isIntegralNumber() || version.asLong() != 1) {
            issues.add(new Issue(null, "schemaVersion", "bad_schema_version"));
        }
        if (!KIND.equals(textOf(spec.get("kind")))) {
            issues.add(new Issue(null, "kind", "bad_kind"));
        }
        if (!issues.isEmpty()) {
            return issues; // not a page document: the other checks would only add noise
        }
        if (serializedLength(spec) > limits.getSpecChars()) {
            issues.add(new Issue(null, null, "too_big"));
            return issues;
        }
        if (deeperThan(spec, 1, limits.getDepth())) {
            issues.add(new Issue(null, null, "too_deep"));
            return issues;
        }
        checkStructure((ObjectNode) spec, issues);
        Iterator<Map.Entry<String, JsonNode>> fields = spec.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String key = entry.getKey();
            JsonNode value = entry.getValue();
            if ("page".equals(key) && value.isObject()) {
                walkFields(value, "page", mode, issues);
                checkPageImageLength(value, issues);
            } else if ("sections".equals(key) && value.isArray()) {
                for (JsonNode section : value) {
                    walkNode(section, mode, issues);
                }
            } else {
                walk(value, key, Place.ANYWHERE, new Where(null, key), mode, issues);
            }
        }
        return issues;
    }

    /** The page's gallery picture is stored in a column of IMAGE_MAX characters (see pageFieldIssues). */
    private void checkPageImageLength(JsonNode page, List<Issue> issues) {
        JsonNode image = page.get("image");
        Issue tooLong = new Issue("page", "image", "too_long");
        if (image != null && image.isTextual() && image.textValue().length() > IMAGE_MAX && !issues.contains(tooLong)) {
            issues.add(tooLong);
        }
    }

    /** Sections, cells and blocks: their shape, ids, types and counts. */
    private void checkStructure(ObjectNode spec, List<Issue> issues) {
        if (!spec.path("page").isObject()) {
            issues.add(new Issue("page", null, "bad_page"));
        }
        JsonNode sections = spec.get("sections");
        if (sections == null || !sections.isArray()) {
            issues.add(new Issue(null, "sections", "bad_sections"));
            return;
        }
        if (sections.size() > limits.getSections()) {
            issues.add(new Issue(null, "sections", "too_many_sections"));
        }
        Set<String> ids = new HashSet<>();
        int live = 0;
        for (JsonNode section : sections) {
            if (!section.isObject()) {
                issues.add(new Issue(null, "sections", "bad_section"));
                continue;
            }
            String sectionId = checkNode(section, ids, issues);
            boolean sectionShown = !section.path("hidden").asBoolean(false);
            JsonNode cells = section.get("cells");
            if (cells == null || !cells.isArray()) {
                issues.add(new Issue(sectionId, "cells", "bad_cells"));
                continue;
            }
            if (cells.size() > limits.getCellsPerSection()) {
                issues.add(new Issue(sectionId, "cells", "too_many_cells"));
            }
            int blockCount = 0;
            for (JsonNode cell : cells) {
                if (!cell.isObject() || !cell.path("blocks").isArray()) {
                    issues.add(new Issue(sectionId, "cells", "bad_cell"));
                    continue;
                }
                for (JsonNode block : cell.get("blocks")) {
                    blockCount++;
                    if (!block.isObject()) {
                        issues.add(new Issue(sectionId, "blocks", "bad_block"));
                        continue;
                    }
                    String blockId = checkNode(block, ids, issues);
                    boolean shown = sectionShown && !block.path("hidden").asBoolean(false);
                    if (shown && LIVE_TYPES.contains(textOf(block.get("type")))) {
                        live++;
                    }
                    checkGroupShape(block, blockId, issues);
                }
            }
            if (blockCount > limits.getBlocksPerSection()) {
                issues.add(new Issue(sectionId, "blocks", "too_many_blocks"));
            }
        }
        // Hidden blocks never mount, so only shown ones count, the same as the editor's palette.
        if (live > limits.getLiveBlocks()) {
            issues.add(new Issue(null, null, "too_many_live"));
        }
    }

    /** Checks a section's or block's id, type, settings and hidden; returns its id for later issues. */
    private static String checkNode(JsonNode node, Set<String> ids, List<Issue> issues) {
        JsonNode idNode = node.get("id");
        String id = idNode != null && idNode.isTextual() ? idNode.textValue() : null;
        if (id == null || !ID.matcher(id).matches()) {
            issues.add(new Issue(id, "id", "bad_id"));
        } else if (!ids.add(id)) {
            issues.add(new Issue(id, "id", "duplicate_id"));
        }
        String type = textOf(node.get("type"));
        if (type == null || !TYPE.matcher(type).matches()) {
            issues.add(new Issue(id, "type", "bad_type"));
        }
        JsonNode settings = node.get("settings");
        if (settings != null && !settings.isObject()) {
            issues.add(new Issue(id, "settings", "bad_settings"));
        }
        JsonNode hidden = node.get("hidden");
        if (hidden != null && !hidden.isBoolean()) {
            issues.add(new Issue(id, "hidden", "bad_hidden"));
        }
        return id;
    }

    /** A group field is null (none picked yet) or {id, name} with a positive whole-number id. */
    private static void checkGroupShape(JsonNode block, String blockId, List<Issue> issues) {
        JsonNode group = block.path("settings").get("group");
        if (group == null || group.isNull()) {
            return;
        }
        JsonNode id = group.get("id");
        boolean wellFormed = group.isObject() && id != null && id.canConvertToLong() && id.isIntegralNumber() && id.asLong() > 0;
        if (!wellFormed) {
            issues.add(new Issue(blockId, "group", "group_not_allowed"));
        }
    }

    /** A section or block: its settings are named by field, everything else by its own key. */
    private void walkNode(JsonNode node, Mode mode, List<Issue> issues) {
        if (!node.isObject()) {
            return;
        }
        String nodeId = textOf(node.get("id"));
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String key = entry.getKey();
            JsonNode value = entry.getValue();
            if ("settings".equals(key) && value.isObject()) {
                walkFields(value, nodeId, mode, issues);
            } else if ("cells".equals(key) && value.isArray()) {
                for (JsonNode cell : value) {
                    walkCell(cell, nodeId, mode, issues);
                }
            } else {
                walk(value, key, Place.ANYWHERE, new Where(nodeId, key), mode, issues);
            }
        }
    }

    private void walkCell(JsonNode cell, String sectionId, Mode mode, List<Issue> issues) {
        if (!cell.isObject()) {
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = cell.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if ("blocks".equals(entry.getKey()) && entry.getValue().isArray()) {
                for (JsonNode block : entry.getValue()) {
                    walkNode(block, mode, issues);
                }
            } else {
                walk(entry.getValue(), entry.getKey(), Place.ANYWHERE, new Where(sectionId, entry.getKey()), mode, issues);
            }
        }
    }

    /** The fields of a settings object (or the page object): each top-level key names the field. */
    private void walkFields(JsonNode object, String nodeId, Mode mode, List<Issue> issues) {
        Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            walk(entry.getValue(), entry.getKey(), Place.ANYWHERE, new Where(nodeId, entry.getKey()), mode, issues);
        }
    }

    /** Where an issue is reported: the node and the field it belongs to. */
    private record Where(String nodeId, String field) {
        Issue issue(String key) {
            return new Issue(nodeId, field, key);
        }
    }

    /**
     * What a value sits directly in, for the one rule that depends on it: a mailto: link is allowed
     * only as the url of a links-list element and as the link of an item in an API block's saved copy
     * (the editor maps an API's mailto: values to links, as the live block draws them).
     */
    private enum Place { ANYWHERE, LINK_ITEM, SNAPSHOT, SNAPSHOT_ITEM }

    private static boolean mailtoAllowed(String key, Place place) {
        return ("url".equals(key) && place == Place.LINK_ITEM) || ("link".equals(key) && place == Place.SNAPSHOT_ITEM);
    }

    /**
     * Checks one value by the key that holds it, then everything inside it.
     *
     * @param key   the property name holding the value; null for an array element
     * @param place what the value sits directly in (see Place)
     */
    private void walk(JsonNode value, String key, Place place, Where where, Mode mode, List<Issue> issues) {
        if (key != null) {
            String lower = key.toLowerCase(Locale.ROOT);
            // Event-handler and markup keys, and the one key that rewrites a JavaScript object's prototype.
            if (lower.startsWith("on") || lower.equals("html") || lower.equals("script") || key.equals("__proto__")) {
                issues.add(where.issue("bad_key"));
            }
            if (URL_KEYS.contains(key)) {
                String problem = urlIssue(value, mailtoAllowed(key, place), mode);
                if (problem != null) {
                    issues.add(where.issue(problem));
                }
            }
            if ("body".equals(key)) {
                String problem = bodyIssue(value, mode);
                if (problem != null) {
                    issues.add(where.issue(problem));
                }
            }
            if (key.endsWith("_path") && !(value.isNull() || (value.isTextual() && PATH.matcher(value.textValue()).matches()))) {
                issues.add(where.issue("bad_path"));
            }
            if ("snapshot".equals(key) && !snapshotAllowed(value)) {
                issues.add(where.issue("bad_snapshot"));
            }
        }
        if (value.isTextual() && value.textValue().length() > limits.getStringChars()) {
            issues.add(where.issue("too_long"));
        }
        if (value.isObject()) {
            Place inside = "snapshot".equals(key) ? Place.SNAPSHOT : Place.ANYWHERE;
            Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                walk(entry.getValue(), entry.getKey(), inside, where, mode, issues);
            }
        } else if (value.isArray()) {
            Place inElement = "links".equals(key) ? Place.LINK_ITEM
                    : "items".equals(key) && place == Place.SNAPSHOT ? Place.SNAPSHOT_ITEM : null;
            for (JsonNode element : value) {
                if (inElement != null && element.isObject()) {
                    Iterator<Map.Entry<String, JsonNode>> fields = element.fields();
                    while (fields.hasNext()) {
                        Map.Entry<String, JsonNode> entry = fields.next();
                        walk(entry.getValue(), entry.getKey(), inElement, where, mode, issues);
                    }
                } else {
                    walk(element, null, Place.ANYWHERE, where, mode, issues);
                }
            }
        }
    }

    // ---- values ----

    /**
     * Why a value can't be a link or image address, or null when it can: "" or an https:// address
     * without user:password@, a path on this site (one leading slash and no "." or ".." segment,
     * %2e included), mailto: only where mailtoAllowed says, or http://localhost or http://127.0.0.1
     * while the page is a draft (a backend on the student's computer). A published page refuses
     * every address on the student's own computer.
     */
    static String urlIssue(JsonNode value, boolean mailtoAllowed, Mode mode) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            return "bad_url";
        }
        String url = trimLikeJavaScript(value.textValue());
        if (url.isEmpty()) {
            return null;
        }
        if (UNSAFE_URL_CHAR.matcher(url).find()) {
            return "bad_url";
        }
        if (url.startsWith("/")) {
            String path = url.split("[?#]", 2)[0];
            return url.startsWith("//") || DOT_SEGMENT.matcher(path).find() ? "bad_url" : null;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.startsWith("https://")) {
            String host = hostOf(url.substring("https://".length()));
            if (host == null) {
                return "bad_url";
            }
            return mode == Mode.PUBLISH && isLoopbackHost(host) ? LOOPBACK_URL : null;
        }
        if (lower.startsWith("mailto:")) {
            return mailtoAllowed && MAILTO.matcher(url).matches() ? null : "bad_url";
        }
        if (HTTP_LOOPBACK.matcher(url).matches()) {
            return mode == Mode.PUBLISH ? LOOPBACK_URL : null;
        }
        return "bad_url";
    }

    /** The host of an address after its scheme, or null when it has none or names a user (user:password@host). */
    private static String hostOf(String afterScheme) {
        int end = afterScheme.length();
        for (char stop : new char[] {'/', '?', '#'}) {
            int at = afterScheme.indexOf(stop);
            if (at >= 0 && at < end) {
                end = at;
            }
        }
        String authority = afterScheme.substring(0, end);
        if (authority.isEmpty() || authority.contains("@")) {
            return null;
        }
        String host;
        if (authority.startsWith("[")) {
            int close = authority.indexOf(']');
            host = close < 0 ? null : authority.substring(0, close + 1);
        } else {
            int colon = authority.indexOf(':');
            host = colon < 0 ? authority : authority.substring(0, colon);
        }
        return host == null || host.isEmpty() ? null : host.toLowerCase(Locale.ROOT);
    }

    /**
     * A name for the student's own computer, as typed (hostOf lowercases it) with at most one trailing
     * dot: localhost, a *.localhost name, 127.x.x.x written as four numbers, 0.0.0.0, [::1], [::], or
     * 127.x.x.x mapped into IPv6 as [::ffff:127.x.x.x] or in hex as [::ffff:7fxx:y] (x a hex digit, y
     * one to four). The editor's url-rules.js isLoopbackHost reads the same list.
     */
    private static boolean isLoopbackHost(String host) {
        String name = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
        return name.equals("localhost") || name.endsWith(".localhost") || name.equals("[::1]") || name.equals("[::]")
                || name.equals("0.0.0.0") || (IPV4.matcher(name).matches() && name.startsWith("127."))
                || MAPPED_LOOPBACK.matcher(name).matches();
    }

    /**
     * Whether rich text holds something that runs code, checked twice. First each run from < to the
     * next >, after character references are decoded and (for tags and addresses) whitespace and
     * control characters removed, as browsers ignore them in "java&#9;script:": a script, iframe,
     * object or embed tag, a javascript: address or an on...= handler. Then the body parsed as a
     * browser parses it (jsoup), so a ">" inside a quoted attribute can't hide the rest of a tag:
     * see unsafeElements. Text outside tags is shown as text, so it is not checked.
     */
    static boolean unsafeBody(String body) {
        if (body.indexOf('<') < 0) {
            return false;
        }
        return unsafeTagText(body) || unsafeElements(body);
    }

    private static boolean unsafeTagText(String body) {
        Matcher tags = TAG.matcher(body);
        while (tags.find()) {
            String tag = decodeCharacterReferences(tags.group());
            String squeezed = NOT_PRINTED.matcher(tag).replaceAll("").toLowerCase(Locale.ROOT);
            for (String forbidden : FORBIDDEN_IN_TAG) {
                if (squeezed.contains(forbidden)) {
                    return true;
                }
            }
            String spaced = NOT_PRINTED.matcher(tag).replaceAll(" ");
            if (EVENT_HANDLER.matcher(spaced).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The parsed body has a CODE_ELEMENTS element, an attribute named on..., a javascript: or
     * vbscript: value, or an address attribute whose scheme isn't http, https or mailto (data:, say).
     * Attribute values are checked as decoded, with the characters browsers drop removed.
     */
    private static boolean unsafeElements(String body) {
        for (Element element : Jsoup.parseBodyFragment(body).getAllElements()) {
            if (CODE_ELEMENTS.contains(element.normalName())) {
                return true;
            }
            for (Attribute attribute : element.attributes()) {
                String name = attribute.getKey().toLowerCase(Locale.ROOT);
                String value = NOT_PRINTED.matcher(attribute.getValue()).replaceAll("").toLowerCase(Locale.ROOT);
                if (name.startsWith("on") || value.contains("javascript:") || value.contains("vbscript:")) {
                    return true;
                }
                Matcher scheme = SCHEME.matcher(value);
                if (URL_ATTRIBUTES.contains(name) && scheme.find() && !RICH_LINK_SCHEMES.contains(scheme.group(1))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Why rich text can't be stored, or null: bad_body when it isn't text or runs code (unsafeBody),
     * else, on publish, loopback_url when it links to or shows something on the student's own
     * computer (linksToComputer). One problem per body, as urlIssue gives one per address.
     */
    private static String bodyIssue(JsonNode value, Mode mode) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual() || unsafeBody(value.textValue())) {
            return "bad_body";
        }
        return mode == Mode.PUBLISH && linksToComputer(value.textValue()) ? LOOPBACK_URL : null;
    }

    /**
     * Whether rich text has an href or src that urlIssue would refuse on a published page as
     * loopback_url: its value, decoded, read as typed like a url value. So a value holding a
     * character browsers drop (a tab, a soft hyphen) doesn't count: urlIssue calls it bad_url, and
     * the page renderer doesn't show it as a link either. The body is parsed as a browser parses
     * it, so a ">" inside a quoted attribute can't hide an href; then each run from < to the next >
     * is parsed on its own, because jsoup drops an a tag inside a select, which Chrome keeps (and
     * rich-text.js then shows as a link).
     */
    private static boolean linksToComputer(String body) {
        if (body.indexOf('<') < 0) {
            return false;
        }
        if (hasAddressOnComputer(Jsoup.parseBodyFragment(body))) {
            return true;
        }
        Matcher tags = TAG.matcher(body);
        while (tags.find()) {
            if (hasAddressOnComputer(Jsoup.parseBodyFragment(tags.group()))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasAddressOnComputer(Document parsed) {
        for (Element element : parsed.getAllElements()) {
            for (Attribute attribute : element.attributes()) {
                if (LINK_ATTRIBUTES.contains(attribute.getKey().toLowerCase(Locale.ROOT))
                        && LOOPBACK_URL.equals(urlIssue(TextNode.valueOf(attribute.getValue()), false, Mode.PUBLISH))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String decodeCharacterReferences(String text) {
        Matcher references = CHAR_REFERENCE.matcher(text);
        StringBuilder decoded = new StringBuilder();
        while (references.find()) {
            int codePoint;
            try {
                codePoint = references.group(1) != null
                        ? Integer.parseInt(references.group(1), 16)
                        : Integer.parseInt(references.group(2));
            } catch (NumberFormatException e) {
                codePoint = -1; // more digits than an int holds: dropped, like a value past U+10FFFF
            }
            String replacement = Character.isValidCodePoint(codePoint) ? Character.toString(codePoint) : "";
            references.appendReplacement(decoded, Matcher.quoteReplacement(replacement));
        }
        references.appendTail(decoded);
        return decoded.toString()
                .replaceAll("(?i)&colon;?", ":")
                .replaceAll("(?i)&tab;?", "\t")
                .replaceAll("(?i)&newline;?", "\n");
    }

    /**
     * An API block's saved copy: null, or {items, fetched_at, from_loopback} holding only mapped
     * items (title, text, image, link, value, label as strings), within the snapshot limits.
     */
    private boolean snapshotAllowed(JsonNode snapshot) {
        if (snapshot.isNull()) {
            return true;
        }
        if (!snapshot.isObject()) {
            return false;
        }
        Iterator<String> keys = snapshot.fieldNames();
        while (keys.hasNext()) {
            if (!SNAPSHOT_KEYS.contains(keys.next())) {
                return false;
            }
        }
        JsonNode fetchedAt = snapshot.get("fetched_at");
        if (fetchedAt != null && !(fetchedAt.isNull() || fetchedAt.isNumber() || fetchedAt.isTextual())) {
            return false;
        }
        JsonNode fromLoopback = snapshot.get("from_loopback");
        if (fromLoopback != null && !(fromLoopback.isNull() || fromLoopback.isBoolean())) {
            return false;
        }
        JsonNode items = snapshot.get("items");
        if (items != null && !items.isNull()) {
            if (!items.isArray() || items.size() > limits.getApiSnapshotItems()) {
                return false;
            }
            for (JsonNode item : items) {
                if (!itemAllowed(item)) {
                    return false;
                }
            }
        }
        return serializedLength(snapshot) <= limits.getApiSnapshotChars();
    }

    private boolean itemAllowed(JsonNode item) {
        if (!item.isObject()) {
            return false;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = item.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode value = field.getValue();
            if (!SNAPSHOT_ITEM_KEYS.contains(field.getKey()) || !value.isTextual()
                    || value.textValue().length() > limits.getApiItemChars()) {
                return false;
            }
        }
        return true;
    }

    // ---- page settings ----

    /**
     * Problems with the page settings kept in columns: a one-line title of at most 120 characters,
     * a description up to 300, a byline up to 120, a course that is "" or one of the class groups
     * in lower case, and a gallery picture of at most 500 characters that is https:// or a path on
     * this site.
     */
    public List<Issue> pageFieldIssues(PageFields fields, Set<String> courseCodes) {
        List<Issue> issues = new ArrayList<>();
        String title = fields.title() == null ? "" : fields.title().strip();
        if (title.isEmpty()) {
            issues.add(new Issue("page", "title", "required"));
        } else if (title.length() > TITLE_MAX) {
            issues.add(new Issue("page", "title", "too_long"));
        } else if (title.indexOf('\n') >= 0 || title.indexOf('\r') >= 0) {
            issues.add(new Issue("page", "title", "one_line"));
        }
        if (fields.description() != null && fields.description().length() > DESCRIPTION_MAX) {
            issues.add(new Issue("page", "description", "too_long"));
        }
        if (fields.byline() != null && fields.byline().length() > BYLINE_MAX) {
            issues.add(new Issue("page", "byline", "too_long"));
        }
        String course = fields.course() == null ? "" : fields.course();
        if (!course.isEmpty() && !courseCodes.contains(course)) {
            issues.add(new Issue("page", "course", "bad_course"));
        }
        if (fields.image() != null && fields.image().length() > IMAGE_MAX) {
            issues.add(new Issue("page", "image", "too_long"));
        } else if (!imageAllowed(fields.image())) {
            issues.add(new Issue("page", "image", "bad_image"));
        }
        return issues;
    }

    /** A gallery picture: "" or https:// or a path on this site, never a computer's own address. */
    static boolean imageAllowed(String image) {
        if (image == null) {
            return true;
        }
        String value = trimLikeJavaScript(image);
        boolean httpsOrSitePath = value.isEmpty() || value.startsWith("/") || value.toLowerCase(Locale.ROOT).startsWith("https://");
        return httpsOrSitePath && urlIssue(TextNode.valueOf(value), false, Mode.PUBLISH) == null;
    }

    /** The page object's settings, from a parsed spec. */
    public PageFields pageFieldsOf(JsonNode spec) {
        JsonNode page = spec == null ? null : spec.get("page");
        return page != null && page.isObject() ? pageFieldsFrom(page) : new PageFields(null, null, null, null, null);
    }

    /**
     * The page object's settings from a stored spec, without building the whole tree: the gallery
     * reads these for up to galleryMax pages and stops parsing each one once it has seen "page".
     */
    public PageFields pageFieldsOf(String specJson) {
        if (specJson == null || specJson.isEmpty()) {
            return new PageFields(null, null, null, null, null);
        }
        try (JsonParser parser = objectMapper.getFactory().createParser(specJson)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                return new PageFields(null, null, null, null, null);
            }
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String name = parser.currentName();
                JsonToken next = parser.nextToken();
                if ("page".equals(name) && next == JsonToken.START_OBJECT) {
                    JsonNode page = objectMapper.readTree(parser);
                    return pageFieldsFrom(page);
                }
                parser.skipChildren();
            }
        } catch (IOException e) {
            // A stored spec is always valid JSON; an unreadable one shows no page settings.
        }
        return new PageFields(null, null, null, null, null);
    }

    private static PageFields pageFieldsFrom(JsonNode page) {
        return new PageFields(textOf(page.get("title")), textOf(page.get("description")), textOf(page.get("byline")),
                textOf(page.get("course")), textOf(page.get("image")));
    }

    // ---- groups, hidden nodes, blocks ----

    /** Every well-formed group a block's settings point at, in page order. */
    public List<GroupRef> groupRefs(JsonNode spec) {
        List<GroupRef> refs = new ArrayList<>();
        for (JsonNode block : blocksOf(spec)) {
            JsonNode group = block.path("settings").get("group");
            if (group != null && group.isObject() && group.path("id").isIntegralNumber() && group.path("id").asLong() > 0) {
                refs.add(new GroupRef(textOf(block.get("id")), group.get("id").asLong()));
            }
        }
        return refs;
    }

    /** Sets each group's name to the one Spring has, so a page can't claim another group's name. */
    public void rewriteGroupNames(JsonNode spec, Map<Long, String> names) {
        for (JsonNode block : blocksOf(spec)) {
            JsonNode group = block.path("settings").get("group");
            if (group instanceof ObjectNode groupObject && group.path("id").isIntegralNumber()) {
                String name = names.get(group.get("id").asLong());
                if (name != null) {
                    groupObject.put("name", name);
                }
            }
        }
    }

    /** A copy of the spec without its hidden sections and hidden blocks: what a published page shows. */
    public ObjectNode stripHidden(ObjectNode spec) {
        ObjectNode copy = spec.deepCopy();
        JsonNode sections = copy.get("sections");
        if (!(sections instanceof ArrayNode sectionList)) {
            return copy;
        }
        for (int i = sectionList.size() - 1; i >= 0; i--) {
            JsonNode section = sectionList.get(i);
            if (section.path("hidden").asBoolean(false)) {
                sectionList.remove(i);
                continue;
            }
            for (JsonNode cell : section.path("cells")) {
                if (cell.get("blocks") instanceof ArrayNode blocks) {
                    for (int j = blocks.size() - 1; j >= 0; j--) {
                        if (blocks.get(j).path("hidden").asBoolean(false)) {
                            blocks.remove(j);
                        }
                    }
                }
            }
        }
        return copy;
    }

    /** The settings of the members block with this id, if the spec has one. */
    public Optional<JsonNode> membersBlockSettings(JsonNode spec, String blockId) {
        for (JsonNode block : blocksOf(spec)) {
            if (blockId != null && blockId.equals(textOf(block.get("id"))) && "members".equals(textOf(block.get("type")))) {
                return Optional.of(block.path("settings"));
            }
        }
        return Optional.empty();
    }

    private static List<JsonNode> blocksOf(JsonNode spec) {
        List<JsonNode> blocks = new ArrayList<>();
        if (spec == null) {
            return blocks;
        }
        for (JsonNode section : spec.path("sections")) {
            for (JsonNode cell : section.path("cells")) {
                for (JsonNode block : cell.path("blocks")) {
                    if (block.isObject()) {
                        blocks.add(block);
                    }
                }
            }
        }
        return blocks;
    }

    // ---- slugs ----

    /** Why a slug can't be used, or null: "invalid" (the pattern) or "reserved". */
    public String slugIssue(String slug) {
        if (slug == null || !slugPattern.matcher(slug).matches()) {
            return "invalid";
        }
        return limits.getReservedSlugs().contains(slug) ? "reserved" : null;
    }

    // ---- helpers ----

    /** The length Spring compares with the limits: the spec as Jackson writes it. */
    public int serializedLength(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node).length();
        } catch (JsonProcessingException e) {
            return Integer.MAX_VALUE;
        }
    }

    /** Whether a container nests deeper than max levels, counting itself as level. */
    private static boolean deeperThan(JsonNode node, int level, int max) {
        if (!node.isContainerNode()) {
            return false;
        }
        if (level > max) {
            return true;
        }
        for (JsonNode child : node) {
            if (deeperThan(child, level + 1, max)) {
                return true;
            }
        }
        return false;
    }

    static String textOf(JsonNode node) {
        return node != null && node.isTextual() ? node.textValue() : null;
    }

    /**
     * String.trim as JavaScript does it, so an address reads as in the editor's url-rules.js: spaces and
     * line breaks (Unicode ones too) and the byte order mark, but not U+001C to U+001F, which Java's
     * isWhitespace counts. Kept, one of them makes an address bad_url, as in the editor.
     */
    private static String trimLikeJavaScript(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && isJavaScriptSpace(value.charAt(start))) {
            start++;
        }
        while (end > start && isJavaScriptSpace(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(start, end);
    }

    private static boolean isJavaScriptSpace(char c) {
        boolean separator = c >= '\u001c' && c <= '\u001f';
        return (Character.isWhitespace(c) && !separator) || Character.isSpaceChar(c) || c == '\ufeff';
    }
}
