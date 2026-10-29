package com.open.spring.mvc.uibuilder;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.open.spring.mvc.uibuilder.UiBuilderPageService.Caller;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Published UI Builder pages under /api/ui-builder/published. The gallery and a page view are open
 * to everyone (SecurityConfig permits these two GETs): signed-in members see every published page,
 * anonymous visitors only a copy a teacher approved for everyone, and never a person's name.
 * Messaging the owner and listing a members block need a login and pass UiBuilderRequestGuard.
 * There is no class-level @PreAuthorize, because of the two public routes.
 */
@RestController
@RequestMapping("/api/ui-builder/published")
public class UiBuilderPublishedApiRestController {

    /** DTO for a gallery card. */
    public record GalleryItem(String slug, String title, String description, String byline, String course,
            String image, Long publishedAt, String visibility) {}

    /** Who made a page: the owner's name (signed-in viewers only) and the page's group. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MadeBy(String owner, String group) {}

    /** A group a chat or members block uses; viewerIsMember lets the viewer show the chat (not access control). */
    public record GroupView(String name, boolean viewerIsMember) {}

    /** Whether the viewer is signed in to Spring, and their name. */
    public record Viewer(boolean signedIn, @JsonInclude(JsonInclude.Include.NON_NULL) String name) {}

    /**
     * DTO for a published page. spec is the copy this viewer may see; publishedVersion is its version.
     * pageId is sent only to someone who can edit the page. groups is keyed by group id.
     */
    public record PublishedView(
            String slug,
            String title,
            String description,
            String byline,
            String course,
            String image,
            Long publishedAt,
            Long publishedVersion,
            String visibility,
            JsonNode spec,
            boolean canEdit,
            @JsonInclude(JsonInclude.Include.NON_NULL) Long pageId,
            MadeBy madeBy,
            Map<String, GroupView> groups,
            Viewer viewer) {}

    /** DTO for POST /{slug}/message-owner: the direct-message conversation to open at /dm?dm={id}. */
    public record ConversationRef(Long conversationId) {}

    /** DTO for a name in a members block. */
    public record MemberName(String name) {}

    private final UiBuilderPageService service;
    private final UiBuilderRequestGuard guard;

    public UiBuilderPublishedApiRestController(UiBuilderPageService service, UiBuilderRequestGuard guard) {
        this.service = service;
        this.guard = guard;
    }

    /** GET /published?course= - public: the gallery, newest first. */
    @GetMapping
    public ResponseEntity<List<GalleryItem>> gallery(@RequestParam(required = false) String course,
            Authentication authentication) {
        Caller viewer = service.caller(authentication).orElse(null);
        return UiBuilderException.respond(service.gallery(viewer, course));
    }

    /** GET /published/{slug} - public: one page, or 404 {"error":"not_found"} when this viewer may not see it. */
    @GetMapping("/{slug}")
    public ResponseEntity<PublishedView> page(@PathVariable String slug, Authentication authentication) {
        Caller viewer = service.caller(authentication).orElse(null);
        return UiBuilderException.respond(service.published(viewer, slug));
    }

    /** POST /published/{slug}/message-owner - opens (or reuses) a direct message with the page's owner. */
    @PostMapping("/{slug}/message-owner")
    public ResponseEntity<ConversationRef> messageOwner(@PathVariable String slug, Authentication authentication,
            HttpServletRequest request) {
        Caller caller = signedIn(authentication, request);
        return UiBuilderException.respond(service.messageOwner(caller, slug));
    }

    /** GET /published/{slug}/members/{blockId} - the names in that members block's group. */
    @GetMapping("/{slug}/members/{blockId}")
    public ResponseEntity<List<MemberName>> members(@PathVariable String slug, @PathVariable String blockId,
            Authentication authentication, HttpServletRequest request) {
        Caller caller = signedIn(authentication, request);
        return UiBuilderException.respond(service.members(caller, slug, blockId));
    }

    private Caller signedIn(Authentication authentication, HttpServletRequest request) {
        guard.check(request);
        return service.caller(authentication).orElseThrow(UiBuilderException::signInRequired);
    }
}
