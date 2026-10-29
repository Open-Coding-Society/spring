package com.open.spring.mvc.uibuilder;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.databind.JsonNode;
import com.open.spring.mvc.uibuilder.UiBuilderPageService.Caller;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The signed-in UI Builder routes under /api/ui-builder: an editor's pages, drafts, publishing,
 * visibility, revisions and the editing lease. This class only turns HTTP into UiBuilderPageService
 * calls and their results into responses; the rules, including who may do what, are in the service.
 * Every route first passes UiBuilderRequestGuard. A page the caller can't edit is answered 404,
 * never 403, so ids don't reveal which pages exist.
 */
@RestController
@RequestMapping("/api/ui-builder")
@PreAuthorize("isAuthenticated()")
public class UiBuilderPageApiRestController {

    /**
     * DTO for POST /pages. groupId is kept as JSON so that, as in SaveRequest, a left-out group
     * and "groupId": null can be told apart.
     */
    public record CreateRequest(String title, JsonNode spec, String description, String byline, String course,
            String image, JsonNode groupId) {}

    /**
     * DTO for PUT /pages/{id}. version is the draft version the editor started from. Page fields
     * left out keep their values; "groupId": null removes the page's group. newRevision true (the
     * editor's "Keep mine" after a conflict) keeps this save in a revision of its own, so the copy
     * it replaces stays in Revisions even when the same person saved it minutes ago.
     */
    public record SaveRequest(Long version, JsonNode spec, String title, String description, String byline,
            String course, String image, JsonNode groupId, Boolean newRevision) {}

    /** DTO for POST /pages/{id}/publish; slug is needed on the first publish only. */
    public record PublishRequest(Long version, String slug) {}

    /**
     * DTO for POST /pages/{id}/visibility: SIGNED_IN or PUBLIC, and when a teacher's approval runs out.
     * A teacher's PUBLIC also sends publishedVersion, the published version they reviewed.
     */
    public record VisibilityRequest(String visibility, Long until, Long publishedVersion) {}

    /** DTO for POST /pages/visibility/bulk. */
    public record BulkVisibilityRequest(String course, Long until) {}

    /** DTO for POST /pages/{id}/revisions/{revisionId}/restore. */
    public record RestoreRequest(Long version) {}

    /** Who has the page open in the editor, until when, and whether that is the caller. */
    public record LeaseView(String holderName, long expiresAt, boolean mine) {}

    /**
     * DTO for a page in a list. version is the draft version to send back when saving. visibility
     * is PUBLIC from a teacher's approval until it is withdrawn; publicVersion is the published
     * version approved. publicPending means a newer publish isn't the approved one (visitors still
     * get the approved copy), and publicExpired that the approval's until has passed (visitors get
     * nothing), so either way a teacher can approve again.
     */
    public record PageSummary(
            Long id,
            String slug,
            String title,
            String description,
            String byline,
            String course,
            String image,
            String status,
            String visibility,
            boolean publicRequested,
            Long publicUntil,
            boolean publicPending,
            boolean publicExpired,
            Long publicVersion,
            Long groupId,
            String groupName,
            String ownerName,
            @JsonProperty("isOwner") boolean isOwner,
            long version,
            Long updatedAt,
            String updatedByName,
            Long publishedAt,
            Long publishedVersion,
            boolean hasUnpublishedChanges,
            LeaseView lease) {}

    /** DTO for one page: its summary fields and the draft spec, as one JSON object. */
    public record PageDetail(@JsonUnwrapped PageSummary summary, JsonNode spec) {}

    /** DTO for a revision in the list (without its spec). */
    public record RevisionSummary(Long revisionId, long pageVersion, String kind, String savedByName, long savedAt) {}

    /** DTO for one revision with its spec. */
    public record RevisionDetail(Long revisionId, long pageVersion, String kind, String savedByName, long savedAt,
            JsonNode spec) {}

    /** DTO for GET /slug-available; reason is invalid, reserved or taken. */
    public record SlugAvailability(String slug, boolean available,
            @JsonInclude(JsonInclude.Include.NON_NULL) String reason) {}

    /** DTO for a group the caller may put on a page. */
    public record GroupOption(Long id, String name) {}

    /** DTO for POST /pages/visibility/bulk. */
    public record BulkResult(int updated) {}

    private final UiBuilderPageService service;
    private final UiBuilderRequestGuard guard;

    public UiBuilderPageApiRestController(UiBuilderPageService service, UiBuilderRequestGuard guard) {
        this.service = service;
        this.guard = guard;
    }

    /** GET /pages?scope=mine (default): the caller's pages; scope=class&course= lists a class for teachers. */
    @GetMapping("/pages")
    public ResponseEntity<List<PageSummary>> list(@RequestParam(defaultValue = "mine") String scope,
            @RequestParam(required = false) String course, Authentication authentication, HttpServletRequest request) {
        Caller caller = signedIn(authentication, request);
        return UiBuilderException.respond(service.list(caller, scope, course));
    }

    /** POST /pages - a new draft; 201 with slug null (it is chosen on the first publish), or 409 too_many_pages. */
    @PostMapping("/pages")
    public ResponseEntity<PageDetail> create(@RequestBody CreateRequest body, Authentication authentication,
            HttpServletRequest request) {
        Caller caller = signedIn(authentication, request);
        return UiBuilderException.respond(service.create(caller, body));
    }

    @GetMapping("/pages/{id:\\d+}")
    public ResponseEntity<PageDetail> open(@PathVariable long id, Authentication authentication,
            HttpServletRequest request) {
        Caller caller = signedIn(authentication, request);
        return UiBuilderException.respond(service.open(caller, id));
    }

    /** PUT /pages/{id} - saves the draft; 409 version_conflict when someone saved since the editor's version. */
    @PutMapping("/pages/{id:\\d+}")
    public ResponseEntity<PageDetail> save(@PathVariable long id, @RequestBody SaveRequest body,
            Authentication authentication, HttpServletRequest request) {
        Caller caller = signedIn(authentication, request);
        return UiBuilderException.respond(service.save(caller, id, body));
    }

    @DeleteMapping("/pages/{id:\\d+}")
    public ResponseEntity<Void> delete(@PathVariable long id, Authentication authentication,
            HttpServletRequest request) {
        Caller caller = signedIn(authentication, request);
        return UiBuilderException.respond(service.delete(caller, id));
    }

    @PostMapping("/pages/{id:\\d+}/publish")
    public ResponseEntity<PageDetail> publish(@PathVariable long id, @RequestBody PublishRequest body,
            Authentication authentication, HttpServletRequest request) {
        Caller caller = signedIn(authentication, request);
        return UiBuilderException.respond(service.publish(caller, id, body));
    }

    @PostMapping("/pages/{id:\\d+}/unpublish")
    public ResponseEntity<PageDetail> unpublish(@PathVariable long id, Authentication authentication,
            HttpServletRequest request) {
        Caller caller = signedIn(authentication, request);
        return UiBuilderException.respond(service.unpublish(caller, id));
    }

    /** POST /pages/{id}/visibility - 409 published_changed when a teacher's publishedVersion isn't the published one. */
    @PostMapping("/pages/{id:\\d+}/visibility")
    public ResponseEntity<PageDetail> visibility(@PathVariable long id, @RequestBody VisibilityRequest body,
            Authentication authentication, HttpServletRequest request) {
        Caller caller = signedIn(authentication, request);
        return UiBuilderException.respond(service.setVisibility(caller, id, body));
    }

    /** POST /pages/visibility/bulk - a teacher approves every published page of a course for everyone. */
    @PostMapping("/pages/visibility/bulk")
    public ResponseEntity<BulkResult> bulkVisibility(@RequestBody BulkVisibilityRequest body,
            Authentication authentication, HttpServletRequest request) {
        Caller caller = signedIn(authentication, request);
        return UiBuilderException.respond(service.approveCourse(caller, body));
    }

    @GetMapping("/pages/{id:\\d+}/revisions")
    public ResponseEntity<List<RevisionSummary>> revisions(@PathVariable long id, Authentication authentication,
            HttpServletRequest request) {
        Caller caller = signedIn(authentication, request);
        return UiBuilderException.respond(service.revisions(caller, id));
    }

    @GetMapping("/pages/{id:\\d+}/revisions/{revisionId:\\d+}")
    public ResponseEntity<RevisionDetail> revision(@PathVariable long id, @PathVariable long revisionId,
            Authentication authentication, HttpServletRequest request) {
        Caller caller = signedIn(authentication, request);
        return UiBuilderException.respond(service.revision(caller, id, revisionId));
    }

    /** POST /pages/{id}/revisions/{revisionId}/restore - makes that copy the draft again (never publishes). */
    @PostMapping("/pages/{id:\\d+}/revisions/{revisionId:\\d+}/restore")
    public ResponseEntity<PageDetail> restore(@PathVariable long id, @PathVariable long revisionId,
            @RequestBody RestoreRequest body, Authentication authentication, HttpServletRequest request) {
        Caller caller = signedIn(authentication, request);
        return UiBuilderException.respond(service.restore(caller, id, revisionId, body));
    }

    /** POST /pages/{id}/lease - takes the editing lease if it is free, the caller's or expired, and renews it. */
    @PostMapping("/pages/{id:\\d+}/lease")
    public ResponseEntity<LeaseView> lease(@PathVariable long id, Authentication authentication,
            HttpServletRequest request) {
        Caller caller = signedIn(authentication, request);
        return UiBuilderException.respond(service.lease(caller, id, false));
    }

    /** POST /pages/{id}/lease/take - takes the lease from whoever holds it ("Take over"). */
    @PostMapping("/pages/{id:\\d+}/lease/take")
    public ResponseEntity<LeaseView> takeLease(@PathVariable long id, Authentication authentication,
            HttpServletRequest request) {
        Caller caller = signedIn(authentication, request);
        return UiBuilderException.respond(service.lease(caller, id, true));
    }

    @DeleteMapping("/pages/{id:\\d+}/lease")
    public ResponseEntity<Void> releaseLease(@PathVariable long id, Authentication authentication,
            HttpServletRequest request) {
        Caller caller = signedIn(authentication, request);
        return UiBuilderException.respond(service.releaseLease(caller, id));
    }

    /** POST /pages/{id}/editors/refresh - copies the page group's current members in as editors again. */
    @PostMapping("/pages/{id:\\d+}/editors/refresh")
    public ResponseEntity<PageDetail> refreshEditors(@PathVariable long id, Authentication authentication,
            HttpServletRequest request) {
        Caller caller = signedIn(authentication, request);
        return UiBuilderException.respond(service.refreshEditors(caller, id));
    }

    /** GET /slug-available?slug= - advisory: publishing checks again. */
    @GetMapping("/slug-available")
    public ResponseEntity<SlugAvailability> slugAvailable(@RequestParam(defaultValue = "") String slug,
            Authentication authentication, HttpServletRequest request) {
        signedIn(authentication, request);
        return UiBuilderException.respond(service.slugAvailable(slug));
    }

    /** GET /my-groups - the groups the caller may put on a page (not class groups or course channels). */
    @GetMapping("/my-groups")
    public ResponseEntity<List<GroupOption>> myGroups(Authentication authentication, HttpServletRequest request) {
        Caller caller = signedIn(authentication, request);
        return UiBuilderException.respond(service.myGroups(caller));
    }

    /** The guard runs first, so a request from another site learns nothing, then the account is looked up. */
    private Caller signedIn(Authentication authentication, HttpServletRequest request) {
        guard.check(request);
        return service.caller(authentication).orElseThrow(UiBuilderException::signInRequired);
    }
}
