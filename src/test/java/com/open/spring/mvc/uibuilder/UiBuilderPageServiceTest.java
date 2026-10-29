package com.open.spring.mvc.uibuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.uibuilder.UiBuilderPageApiRestController.BulkResult;
import com.open.spring.mvc.uibuilder.UiBuilderPageApiRestController.BulkVisibilityRequest;
import com.open.spring.mvc.uibuilder.UiBuilderPageApiRestController.CreateRequest;
import com.open.spring.mvc.uibuilder.UiBuilderPageApiRestController.LeaseView;
import com.open.spring.mvc.uibuilder.UiBuilderPageApiRestController.PageDetail;
import com.open.spring.mvc.uibuilder.UiBuilderPageApiRestController.PageSummary;
import com.open.spring.mvc.uibuilder.UiBuilderPageApiRestController.PublishRequest;
import com.open.spring.mvc.uibuilder.UiBuilderPageApiRestController.RestoreRequest;
import com.open.spring.mvc.uibuilder.UiBuilderPageApiRestController.RevisionSummary;
import com.open.spring.mvc.uibuilder.UiBuilderPageApiRestController.SaveRequest;
import com.open.spring.mvc.uibuilder.UiBuilderPageApiRestController.VisibilityRequest;
import com.open.spring.mvc.uibuilder.UiBuilderPageService.Caller;
import com.open.spring.mvc.uibuilder.UiBuilderPageService.Outcome;
import com.open.spring.mvc.uibuilder.UiBuilderPageService.Result;
import com.open.spring.mvc.uibuilder.UiBuilderPublishedApiRestController.GalleryItem;
import com.open.spring.mvc.uibuilder.UiBuilderPublishedApiRestController.MemberName;
import com.open.spring.mvc.uibuilder.UiBuilderPublishedApiRestController.PublishedView;
import com.open.spring.mvc.uibuilder.UiBuilderSpecRules.Issue;

/** The service's rules over an in-memory store with a fixed clock: access, versions, publishing, groups, leases and revisions. */
class UiBuilderPageServiceTest {

    private UiBuilderTestStore store;
    private UiBuilderPageService service;
    private Person hopPerson;
    private Caller hop;
    private Caller ada;
    private Caller mallory;
    private Caller niko;

    @BeforeEach
    void setUp() {
        store = new UiBuilderTestStore();
        service = store.service;
        hopPerson = store.person(1, "hop", "Hop Student");
        hop = UiBuilderTestStore.student(hopPerson);
        ada = UiBuilderTestStore.student(store.person(2, "ada", "Ada Student"));
        mallory = UiBuilderTestStore.student(store.person(3, "mallory", "Mallory Student"));
        niko = UiBuilderTestStore.teacher(store.person(4, "niko", "Niko Teacher"));
        store.group(40, "Binary Beasts", 1L, 2L);
        store.group(41, "Chess Club", 3L);
        store.group(50, "CSP", 1L, 2L, 3L);
        store.group(51, "csp-announcements", 1L);
        store.group(52, "csa-week-12", 1L);
        store.group(53, "Lessons", 1L);
    }

    private PageDetail create(Caller caller, ObjectNode spec) {
        Result<PageDetail> result = service.create(caller,
                new CreateRequest(null, spec, null, null, null, null, null));
        assertEquals(Outcome.CREATED, result.outcome(), () -> String.valueOf(result.details()));
        return result.body();
    }

    private PageDetail create(Caller caller, String title) {
        return create(caller, store.simplePage(title));
    }

    private Result<PageDetail> save(Caller caller, PageDetail page, ObjectNode spec) {
        return service.save(caller, page.summary().id(),
                new SaveRequest(page.summary().version(), spec, null, null, null, null, null, null, null));
    }

    private Result<PageDetail> saveWithGroup(Caller caller, PageDetail page, JsonNode groupId) {
        return service.save(caller, page.summary().id(), new SaveRequest(page.summary().version(),
                (ObjectNode) page.spec(), null, null, null, null, null, groupId, null));
    }

    private PageDetail publish(Caller caller, PageDetail page, String slug) {
        Result<PageDetail> result = service.publish(caller, page.summary().id(),
                new PublishRequest(page.summary().version(), slug));
        assertEquals(Outcome.OK, result.outcome(), () -> String.valueOf(result.details()));
        return result.body();
    }

    // ---- identity ----

    @Test
    void theCallerComesFromTheLoginAndTeachersFromTheirRoles() {
        assertEquals(hop, service.caller(UiBuilderTestStore.login(hopPerson, "ROLE_USER", "ROLE_STUDENT")).orElseThrow());
        Caller admin = service.caller(UiBuilderTestStore.login(hopPerson, "ROLE_ADMIN")).orElseThrow();
        assertTrue(admin.teacher());

        assertTrue(service.caller(null).isEmpty());
        assertTrue(service.caller(new AnonymousAuthenticationToken("key", "anonymousUser",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"))).isEmpty());
        assertTrue(service.caller(new UsernamePasswordAuthenticationToken("hop", null)).isEmpty(), "not authenticated");
        assertTrue(service.caller(new UsernamePasswordAuthenticationToken("gone", null, List.of())).isEmpty());
    }

    @Test
    void everyOutcomeHasAnHttpStatus() {
        for (Outcome outcome : EnumSet.allOf(Outcome.class)) {
            assertNotNull(UiBuilderException.statusOf(outcome), outcome.name());
        }
        assertEquals(HttpStatus.CONFLICT, UiBuilderException.statusOf(Outcome.VERSION_CONFLICT));
        assertEquals("version_conflict", UiBuilderException.codeOf(Outcome.VERSION_CONFLICT));
    }

    // ---- creating and access ----

    @Test
    void aNewPageIsTheCallersDraftAtVersionZero() {
        PageDetail page = create(hop, "Flood Watch");
        PageSummary summary = page.summary();

        assertNull(summary.slug());
        assertEquals("Flood Watch", summary.title());
        assertEquals("csp", summary.course());
        assertEquals("DRAFT", summary.status());
        assertEquals("SIGNED_IN", summary.visibility());
        assertEquals(0, summary.version());
        assertTrue(summary.isOwner());
        assertEquals("Hop Student", summary.ownerName());
        assertEquals("Hop Student", summary.updatedByName());
        assertEquals(UiBuilderTestStore.NOW.toEpochMilli(), summary.updatedAt());
        assertFalse(summary.hasUnpublishedChanges());
        assertEquals("Flood Watch", page.spec().at("/page/title").asText());
        assertEquals(List.of("SAVE"), store.revisionsOf(summary.id()).stream().map(UiBuilderPageRevisionEntity::getKind).toList());
    }

    @Test
    void pageFieldsInTheRequestWinAndAreWrittenIntoTheSpec() {
        Result<PageDetail> result = service.create(hop, new CreateRequest("  Flood Watch 2  ",
                store.simplePage("Old title"), "Levels", "Binary Beasts", "csa", "/images/fw.png", null));

        PageDetail page = result.body();
        assertEquals("Flood Watch 2", page.summary().title());
        assertEquals("csa", page.summary().course());
        assertEquals("Flood Watch 2", page.spec().at("/page/title").asText());
        assertEquals("Binary Beasts", page.spec().at("/page/byline").asText());
        assertEquals("/images/fw.png", page.spec().at("/page/image").asText());
    }

    @Test
    void anInvalidSpecOrPageFieldIsRefusedWithItsProblems() {
        Result<PageDetail> invalid = service.create(hop, new CreateRequest(null, store.fixture("invalid/bad-id.json"),
                null, null, null, null, null));
        assertEquals(Outcome.INVALID_SPEC, invalid.outcome());
        assertEquals(List.of(new Issue("block-1", "id", "bad_id")), invalid.details().get("details"));

        Result<PageDetail> course = service.create(hop, new CreateRequest(null, store.simplePage("x"), null, null,
                "art", null, null));
        assertEquals(List.of(new Issue("page", "course", "bad_course")), course.details().get("details"));

        Result<PageDetail> notAPage = service.create(hop, new CreateRequest("x", IntNode.valueOf(3), null, null, null,
                null, null));
        assertEquals(Outcome.INVALID_SPEC, notAPage.outcome());
        assertTrue(store.pageRows.isEmpty());
    }

    @Test
    void aGalleryPictureOverFiveHundredCharactersIsRefusedNotCut() {
        String tooLong = "https://lh3.googleusercontent.com/" + "a".repeat(467);
        String fits = tooLong.substring(0, 500);
        List<Issue> tooLongIssue = List.of(new Issue("page", "image", "too_long"));

        Result<PageDetail> created = service.create(hop, new CreateRequest(null, store.simplePage("Picture"), null, null,
                null, tooLong, null));
        assertEquals(Outcome.INVALID_SPEC, created.outcome());
        assertEquals(tooLongIssue, created.details().get("details"));

        PageDetail page = create(hop, "Picture");
        ObjectNode spec = store.simplePage("Picture");
        ((ObjectNode) spec.get("page")).put("image", tooLong);
        assertEquals(tooLongIssue, save(hop, page, spec).details().get("details"), "in the spec's page settings too");
        assertEquals("", store.stored(page.summary().id()).getImage());

        PageDetail saved = service.save(hop, page.summary().id(), new SaveRequest(0L, store.simplePage("Picture"), null,
                null, null, null, fits, null, null)).body();
        assertEquals(fits, saved.summary().image());
        assertEquals(fits, saved.spec().at("/page/image").asText());
    }

    @Test
    void anOwnerMayHaveAtMostOwnerPagesMaxPages() {
        store.limits.setOwnerPagesMax(2);
        create(hop, "One");
        create(hop, "Two");

        Result<PageDetail> third = service.create(hop, new CreateRequest(null, store.simplePage("Three"), null, null,
                null, null, null));
        assertEquals(Outcome.TOO_MANY_PAGES, third.outcome());
        assertEquals(Map.of("max", 2), third.details());
        assertEquals(HttpStatus.CONFLICT, UiBuilderException.statusOf(Outcome.TOO_MANY_PAGES));
        assertEquals(Outcome.CREATED, service.create(ada, new CreateRequest(null, store.simplePage("Ada's"), null, null,
                null, null, null)).outcome(), "the limit is per owner");
    }

    @Test
    void someoneWhoCantEditGetsNotFoundEverywhere() {
        PageDetail page = create(hop, "Private");
        long id = page.summary().id();

        assertEquals(Outcome.NOT_FOUND, service.open(mallory, id).outcome());
        assertEquals(Outcome.NOT_FOUND, save(mallory, page, store.simplePage("Hacked")).outcome());
        assertEquals(Outcome.NOT_FOUND, service.publish(mallory, id, new PublishRequest(0L, "hacked")).outcome());
        assertEquals(Outcome.NOT_FOUND, service.delete(mallory, id).outcome());
        assertEquals(Outcome.NOT_FOUND, service.revisions(mallory, id).outcome());
        assertEquals(Outcome.NOT_FOUND, service.lease(mallory, id, true).outcome());
        assertEquals(Outcome.NOT_FOUND, service.setVisibility(mallory, id, new VisibilityRequest("SIGNED_IN", null, null)).outcome());
        assertEquals(Outcome.NOT_FOUND, service.open(hop, 999).outcome(), "a page that doesn't exist looks the same");
        assertEquals("Private", store.stored(id).getTitle());

        assertEquals(Outcome.OK, service.open(niko, id).outcome(), "teachers can open any page");
    }

    @Test
    void theOwnerAndATeacherCanDeleteButOtherEditorsCant() {
        PageDetail page = create(hop, "Team page");
        PageDetail grouped = saveWithGroup(hop, page, IntNode.valueOf(40)).body();
        long id = page.summary().id();
        publish(hop, grouped, "team-page");

        assertEquals(Outcome.FORBIDDEN, service.delete(ada, id).outcome());
        assertEquals(Outcome.NO_CONTENT, service.delete(niko, id).outcome());

        UiBuilderPageEntity row = store.stored(id);
        assertEquals("DELETED", row.getStatus());
        assertEquals("{}", row.getDraftSpec());
        assertNull(row.getPublishedSpec());
        assertEquals("team-page", row.getSlug(), "the slug stays reserved");
        assertTrue(store.revisionsOf(id).isEmpty());
        assertTrue(store.editorsOf(id).isEmpty());
        assertEquals(Outcome.NOT_FOUND, service.open(niko, id).outcome());
        assertEquals("taken", service.slugAvailable("team-page").body().reason());
        assertEquals(Outcome.NOT_FOUND, service.published(hop, "team-page").outcome());
    }

    // ---- versions ----

    @Test
    void savingAnOldVersionIsAConflictThatSaysWhoSavedLast() {
        PageDetail page = create(hop, "Flood Watch");
        store.clock.advance(Duration.ofMinutes(1));
        PageDetail saved = save(hop, page, store.simplePage("Flood Watch v1")).body();
        assertEquals(1, saved.summary().version());

        Result<PageDetail> stale = save(hop, page, store.simplePage("Stale tab"));

        assertEquals(Outcome.VERSION_CONFLICT, stale.outcome());
        assertEquals(Map.of("currentVersion", 1L, "savedByName", "Hop Student",
                "savedAt", UiBuilderTestStore.NOW.plusSeconds(60).toEpochMilli()), stale.details());
        assertEquals("Flood Watch v1", store.stored(page.summary().id()).getTitle());

        Result<PageDetail> missing = service.save(hop, page.summary().id(),
                new SaveRequest(null, store.simplePage("x"), null, null, null, null, null, null, null));
        assertEquals(Outcome.INVALID_SPEC, missing.outcome());
    }

    // ---- publishing ----

    @Test
    void theFirstPublishNeedsAFreeValidSlugAndThenTheSlugIsFixed() {
        PageDetail page = create(hop, "Flood Watch");
        long id = page.summary().id();
        PageDetail other = create(ada, "Other");
        publish(ada, other, "taken-slug");

        assertEquals(Outcome.SLUG_REQUIRED, service.publish(hop, id, new PublishRequest(0L, " ")).outcome());
        assertEquals(Outcome.INVALID_SLUG, service.publish(hop, id, new PublishRequest(0L, "Flood Watch")).outcome());
        assertEquals(Outcome.SLUG_RESERVED, service.publish(hop, id, new PublishRequest(0L, "admin")).outcome());
        assertEquals(Outcome.SLUG_TAKEN, service.publish(hop, id, new PublishRequest(0L, "taken-slug")).outcome());
        assertEquals(Outcome.VERSION_CONFLICT, service.publish(hop, id, new PublishRequest(5L, "flood-watch")).outcome());

        PageDetail published = publish(hop, page, "flood-watch");
        assertEquals("flood-watch", published.summary().slug());
        assertEquals("PUBLISHED", published.summary().status());
        assertEquals(0L, published.summary().publishedVersion());
        assertFalse(published.summary().hasUnpublishedChanges());

        assertEquals(Outcome.SLUG_LOCKED, service.publish(hop, id, new PublishRequest(0L, "new-name")).outcome());
        assertEquals(Outcome.OK, service.publish(hop, id, new PublishRequest(0L, null)).outcome());
        assertEquals(Outcome.OK, service.publish(hop, id, new PublishRequest(0L, "flood-watch")).outcome());
    }

    @Test
    void publishingCopiesTheDraftWithoutItsHiddenParts() throws Exception {
        ObjectNode spec = store.fixture("all-blocks.json");
        ((ObjectNode) spec.at("/sections/1")).put("hidden", true);
        ((ObjectNode) spec.at("/sections/0/cells/0/blocks/1")).put("hidden", true);
        store.group(42, "Binary Beasts", 1L);
        PageDetail page = create(hop, spec);

        PageDetail published = publish(hop, page, "flood-watch");
        assertEquals(5, store.json.readTree(store.stored(page.summary().id()).getDraftSpec()).get("sections").size(),
                "the draft keeps its hidden section");
        PageDetail edited = save(hop, published, store.simplePage("Next version")).body();

        JsonNode copy = service.published(ada, "flood-watch").body().spec();
        assertEquals(4, copy.get("sections").size());
        assertFalse(copy.toString().contains("b-heading01"));
        assertTrue(edited.summary().hasUnpublishedChanges());
        assertEquals("Flood Watch", service.published(ada, "flood-watch").body().title(), "viewers keep the published copy");
        List<String> kinds = store.revisionsOf(page.summary().id()).stream().map(UiBuilderPageRevisionEntity::getKind).toList();
        assertEquals(List.of("SAVE", "PUBLISH", "SAVE"), kinds);
    }

    @Test
    void aDraftCanReadAnApiOnTheTeamsComputerButCantBePublishedThatWay() {
        PageDetail page = create(hop, store.fixture("invalid/loopback-url-on-publish.json"));

        Result<PageDetail> result = service.publish(hop, page.summary().id(), new PublishRequest(0L, "laptop-jokes"));

        assertEquals(Outcome.LOOPBACK_SOURCE, result.outcome());
        assertEquals(List.of(new Issue("b-loop0001", "source", "loopback_url")), result.details().get("details"));
        assertEquals("DRAFT", store.stored(page.summary().id()).getStatus());
    }

    @Test
    void aDraftCanLinkToTheTeamsComputerInRichTextButCantBePublishedThatWay() {
        ObjectNode spec = store.simplePage("Laptop links");
        ObjectNode text = ((ArrayNode) spec.at("/sections/0/cells/0/blocks")).addObject();
        text.put("id", "b-text0001").put("type", "text").put("hidden", false);
        text.putObject("settings").put("body", "Try <a href=\"https://localhost:8443/\">our app</a> before class.");
        PageDetail page = create(hop, spec);

        Result<PageDetail> result = service.publish(hop, page.summary().id(), new PublishRequest(0L, "laptop-links"));

        assertEquals(Outcome.LOOPBACK_SOURCE, result.outcome());
        assertEquals(List.of(new Issue("b-text0001", "body", "loopback_url")), result.details().get("details"));
        assertEquals("DRAFT", store.stored(page.summary().id()).getStatus());
    }

    @Test
    void unpublishingTakesThePageDownAndClearsItsPublicApproval() {
        PageDetail page = publish(hop, create(hop, "Flood Watch"), "flood-watch");
        service.setVisibility(niko, page.summary().id(), new VisibilityRequest("PUBLIC", null, 0L));

        PageDetail down = service.unpublish(niko, page.summary().id()).body();

        assertEquals("DRAFT", down.summary().status());
        assertEquals("SIGNED_IN", down.summary().visibility());
        assertEquals("flood-watch", down.summary().slug(), "the address is kept for the next publish");
        assertNull(store.stored(page.summary().id()).getPublicSpec());
        assertEquals(Outcome.NOT_FOUND, service.published(null, "flood-watch").outcome());
        assertEquals(Outcome.NOT_FOUND, service.published(ada, "flood-watch").outcome());
    }

    // ---- who sees a published page ----

    @Test
    void anonymousVisitorsSeeOnlyTheCopyATeacherApproved() {
        PageDetail page = create(hop, "Flood Watch");
        long id = page.summary().id();
        PageDetail published = publish(hop, page, "flood-watch");
        assertEquals(Outcome.NOT_FOUND, service.published(null, "flood-watch").outcome(), "SIGNED_IN by default");

        PageDetail asked = service.setVisibility(hop, id, new VisibilityRequest("PUBLIC", null, null)).body();
        assertTrue(asked.summary().publicRequested());
        assertEquals("SIGNED_IN", asked.summary().visibility());
        assertEquals(Outcome.NOT_FOUND, service.published(null, "flood-watch").outcome(), "a request changes nothing for viewers");

        PageDetail approved = service.setVisibility(niko, id, new VisibilityRequest("PUBLIC", null, 0L)).body();
        assertEquals("PUBLIC", approved.summary().visibility());
        assertFalse(approved.summary().publicRequested());
        assertFalse(approved.summary().publicPending());

        PageDetail edited = save(hop, published, store.simplePage("Flood Watch v2")).body();
        PageDetail republished = publish(hop, edited, null);
        assertTrue(republished.summary().publicPending());

        PublishedView anonymous = service.published(null, "flood-watch").body();
        assertEquals("Flood Watch", anonymous.title());
        assertEquals(0L, anonymous.publishedVersion());
        assertEquals("PUBLIC", anonymous.visibility());
        assertNull(anonymous.madeBy().owner(), "no person's name for anonymous visitors");
        assertFalse(anonymous.canEdit());
        assertNull(anonymous.pageId());
        assertFalse(anonymous.viewer().signedIn());

        PublishedView member = service.published(ada, "flood-watch").body();
        assertEquals("Flood Watch v2", member.title());
        assertEquals(1L, member.publishedVersion());
        assertEquals("Hop Student", member.madeBy().owner());

        List<GalleryItem> gallery = service.gallery(null, null).body();
        assertEquals(1, gallery.size());
        assertEquals("Flood Watch", gallery.get(0).title(), "the gallery card is the approved copy too");
        assertEquals("Flood Watch v2", service.gallery(ada, null).body().get(0).title());
    }

    @Test
    void aPublicApprovalRunsOutAndOnlyApprovesAPublishedPage() {
        PageDetail page = create(hop, "Flood Watch");
        long id = page.summary().id();
        long inAnHour = UiBuilderTestStore.NOW.plusSeconds(3600).toEpochMilli();

        assertEquals(Outcome.NOT_PUBLISHED, service.setVisibility(niko, id, new VisibilityRequest("PUBLIC", inAnHour, null)).outcome());
        publish(hop, page, "flood-watch");
        assertEquals(Outcome.INVALID_UNTIL, service.setVisibility(niko, id,
                new VisibilityRequest("PUBLIC", UiBuilderTestStore.NOW.toEpochMilli(), 0L)).outcome());
        assertEquals(Outcome.INVALID_VISIBILITY, service.setVisibility(niko, id, new VisibilityRequest("EVERYONE", null, null)).outcome());
        assertEquals(Outcome.OK, service.setVisibility(niko, id, new VisibilityRequest("PUBLIC", inAnHour, 0L)).outcome());
        assertEquals(Outcome.OK, service.published(null, "flood-watch").outcome());
        assertEquals(1, service.gallery(null, "csp").body().size());

        store.clock.advance(Duration.ofHours(2));

        assertEquals(Outcome.NOT_FOUND, service.published(null, "flood-watch").outcome());
        assertTrue(service.gallery(null, null).body().isEmpty());
        assertEquals(Outcome.OK, service.published(ada, "flood-watch").outcome(), "members still see it");

        PageDetail membersOnly = service.setVisibility(hop, id, new VisibilityRequest("SIGNED_IN", null, null)).body();
        assertEquals("SIGNED_IN", membersOnly.summary().visibility());
        assertNull(membersOnly.summary().publicUntil());
    }

    @Test
    void aTeacherApprovesOnlyThePublishedVersionTheyReviewed() {
        PageDetail page = publish(hop, create(hop, "Reviewed"), "flood-watch");
        long id = page.summary().id();
        long reviewed = page.summary().publishedVersion();
        PageDetail edited = save(hop, page, store.simplePage("Never reviewed")).body();
        publish(hop, edited, null); // lands between the teacher's review and the click

        Result<PageDetail> stale = service.setVisibility(niko, id, new VisibilityRequest("PUBLIC", null, reviewed));
        assertEquals(Outcome.PUBLISHED_CHANGED, stale.outcome());
        assertEquals(Map.of("publishedVersion", 1L), stale.details());
        assertNull(store.stored(id).getPublicSpec(), "nothing reaches visitors");
        assertEquals(Outcome.NOT_FOUND, service.published(null, "flood-watch").outcome());
        assertEquals(Outcome.PUBLISHED_CHANGED,
                service.setVisibility(niko, id, new VisibilityRequest("PUBLIC", null, null)).outcome(),
                "a teacher's PUBLIC must name the version");
        assertTrue(service.setVisibility(hop, id, new VisibilityRequest("PUBLIC", null, 99L)).body().summary()
                .publicRequested(), "a student's PUBLIC only asks, whatever version it names");

        PageDetail approved = service.setVisibility(niko, id, new VisibilityRequest("PUBLIC", null, 1L)).body();
        assertEquals("PUBLIC", approved.summary().visibility());
        assertEquals(1L, approved.summary().publicVersion());
        assertEquals("Never reviewed", service.published(null, "flood-watch").body().title());
        assertEquals(HttpStatus.CONFLICT, UiBuilderException.statusOf(Outcome.PUBLISHED_CHANGED));
    }

    @Test
    void approvingAPageUnpublishedSinceTheReviewIsNotPublishedLikeOneNeverPublished() {
        PageDetail page = publish(hop, create(hop, "Reviewed"), "flood-watch");
        long id = page.summary().id();
        long reviewed = page.summary().publishedVersion();
        assertEquals(Outcome.OK, service.unpublish(hop, id).outcome()); // lands between the teacher's review and the click

        Result<PageDetail> gone = service.setVisibility(niko, id, new VisibilityRequest("PUBLIC", null, reviewed));
        assertEquals(Outcome.NOT_PUBLISHED, gone.outcome(), "not published_changed: nothing was published again");
        assertEquals(Map.of(), gone.details());
        assertNull(store.stored(id).getPublicSpec(), "nothing reaches visitors");
        assertEquals(Outcome.NOT_FOUND, service.published(null, "flood-watch").outcome());
        assertEquals(Outcome.NOT_PUBLISHED, service.setVisibility(niko, id, new VisibilityRequest("PUBLIC", null, null)).outcome());

        long never = create(hop, "Never published").summary().id();
        assertEquals(gone, service.setVisibility(niko, never, new VisibilityRequest("PUBLIC", null, reviewed)),
                "the answer a page never published gets");
        assertEquals(HttpStatus.CONFLICT, UiBuilderException.statusOf(Outcome.NOT_PUBLISHED));
    }

    @Test
    void anApprovalThatRanOutIsMembersOnlyAndTheSummarySaysWhy() {
        PageDetail page = publish(hop, create(hop, "Flood Watch"), "flood-watch");
        long id = page.summary().id();
        long inAnHour = UiBuilderTestStore.NOW.plusSeconds(3600).toEpochMilli();
        service.setVisibility(niko, id, new VisibilityRequest("PUBLIC", inAnHour, 0L));
        assertEquals("PUBLIC", service.published(ada, "flood-watch").body().visibility());
        assertEquals("PUBLIC", service.gallery(ada, null).body().get(0).visibility());
        PageSummary live = service.open(niko, id).body().summary();
        assertFalse(live.publicExpired());
        assertEquals(0L, live.publicVersion());

        store.clock.advance(Duration.ofHours(2));

        assertEquals("SIGNED_IN", service.published(ada, "flood-watch").body().visibility());
        assertEquals("SIGNED_IN", service.gallery(ada, null).body().get(0).visibility());
        PageSummary expired = service.list(niko, "class", "csp").body().get(0);
        assertEquals("PUBLIC", expired.visibility(), "the approval is still on record");
        assertTrue(expired.publicExpired());
        assertFalse(expired.publicPending());
        PageDetail again = service.setVisibility(niko, id, new VisibilityRequest("PUBLIC", null, 0L)).body();
        assertFalse(again.summary().publicExpired(), "approved again without taking it down first");
        assertEquals(Outcome.OK, service.published(null, "flood-watch").outcome());
    }

    @Test
    void aNewerPublishOfAnApprovedPageIsPendingWithTheApprovedVersionNamed() {
        PageDetail page = publish(hop, create(hop, "Flood Watch"), "flood-watch");
        long id = page.summary().id();
        service.setVisibility(niko, id, new VisibilityRequest("PUBLIC", null, 0L));
        PageDetail republished = publish(hop, save(hop, page, store.simplePage("Flood Watch v2")).body(), null);

        PageSummary summary = republished.summary();
        assertTrue(summary.publicPending());
        assertFalse(summary.publicExpired());
        assertEquals(0L, summary.publicVersion());
        assertEquals(1L, summary.publishedVersion());
        assertEquals("PUBLIC", service.published(ada, "flood-watch").body().visibility(), "visitors still get version 0");
        assertFalse(service.setVisibility(niko, id, new VisibilityRequest("PUBLIC", null, 1L)).body().summary().publicPending());
    }

    @Test
    void galleryCardsComeFromEachCopysCardNotItsSpec() throws Exception {
        PageDetail page = publish(hop, create(hop, "Flood Watch"), "flood-watch");
        long id = page.summary().id();
        service.setVisibility(niko, id, new VisibilityRequest("PUBLIC", null, 0L));
        UiBuilderPageEntity row = store.stored(id);
        assertEquals("Flood Watch", store.json.readTree(row.getPublishedCard()).get("title").asText());
        assertEquals(row.getPublishedCard(), row.getPublicCard());

        assertEquals("Flood Watch", service.gallery(ada, null).body().get(0).title());
        assertEquals("Flood Watch", service.gallery(null, null).body().get(0).title());
        verify(store.pages, never()).findPublishedSpecById(anyLong());
        verify(store.pages, never()).findPublicSpecById(anyLong());

        // A page published and approved before cards were kept: the gallery reads that copy instead.
        row.setPublishedCard(null);
        row.setPublicCard(null);
        assertEquals("Flood Watch", service.gallery(ada, null).body().get(0).title());
        assertEquals("Flood Watch", service.gallery(null, null).body().get(0).title());

        service.unpublish(hop, id);
        assertNull(store.stored(id).getPublishedCard());
        assertNull(store.stored(id).getPublicCard());
    }

    @Test
    void aTeacherCanApproveEveryPublishedPageOfACourse() {
        PageDetail csp1 = publish(hop, create(hop, "One"), "csp-one");
        publish(ada, create(ada, "Two"), "csp-two");
        create(ada, "Draft only");
        Result<PageDetail> csa = service.create(ada, new CreateRequest(null, store.simplePage("CSA page"), null, null,
                "csa", null, null));
        publish(ada, csa.body(), "csa-page");
        long until = UiBuilderTestStore.NOW.plusSeconds(86_400).toEpochMilli();

        assertEquals(Outcome.FORBIDDEN, service.approveCourse(hop, new BulkVisibilityRequest("csp", until)).outcome());
        assertEquals(Outcome.INVALID_COURSE, service.approveCourse(niko, new BulkVisibilityRequest("art", until)).outcome());
        Result<BulkResult> result = service.approveCourse(niko, new BulkVisibilityRequest("csp", until));

        assertEquals(2, result.body().updated());
        assertEquals(List.of("csp-two", "csp-one"), service.gallery(null, null).body().stream().map(GalleryItem::slug).toList());
        assertEquals(until, store.stored(csp1.summary().id()).getPublicUntil());
        assertEquals(Outcome.NOT_FOUND, service.published(null, "csa-page").outcome());
    }

    // ---- groups ----

    @Test
    void settingTheGroupNeedsAMemberAndAGroupThatIsntAClassOrChannel() {
        PageDetail page = create(hop, "Team page");

        assertEquals(Outcome.INVALID_GROUP, saveWithGroup(hop, page, IntNode.valueOf(41)).outcome(), "not hop's group");
        for (long restricted : List.of(50L, 51L, 52L, 53L)) {
            assertEquals(Outcome.INVALID_GROUP, saveWithGroup(hop, page, IntNode.valueOf((int) restricted)).outcome(),
                    "class groups and course channels");
        }
        assertEquals(Outcome.INVALID_GROUP, saveWithGroup(hop, page, IntNode.valueOf(999)).outcome());
        assertEquals(Outcome.INVALID_GROUP, saveWithGroup(hop, page,
                store.json.getNodeFactory().textNode("forty")).outcome());

        PageDetail grouped = saveWithGroup(hop, page, IntNode.valueOf(40)).body();
        assertEquals(40L, grouped.summary().groupId());
        assertEquals("Binary Beasts", grouped.summary().groupName());
        assertEquals(List.of(2L), store.editorsOf(page.summary().id()), "the other members become editors");

        assertEquals(Outcome.OK, service.open(ada, page.summary().id()).outcome());
        assertEquals(Outcome.FORBIDDEN, saveWithGroup(ada, grouped, NullNode.getInstance()).outcome(),
                "only the owner or a teacher changes the group");
        PageDetail adaSave = saveWithGroup(ada, grouped, null).body();
        assertEquals(40L, adaSave.summary().groupId(), "leaving groupId out keeps it");

        PageDetail ungrouped = saveWithGroup(hop, adaSave, NullNode.getInstance()).body();
        assertNull(ungrouped.summary().groupId());
        assertTrue(store.editorsOf(page.summary().id()).isEmpty());
        assertEquals(Outcome.NOT_FOUND, service.open(ada, page.summary().id()).outcome());

        PageDetail teacherSet = saveWithGroup(niko, ungrouped, IntNode.valueOf(41)).body();
        assertEquals(41L, teacherSet.summary().groupId(), "teachers need not be members");
        assertEquals(List.of(3L), store.editorsOf(page.summary().id()));
    }

    @Test
    void editorsComeFromTheGroupOnlyWhenItIsSetOrRefreshed() {
        PageDetail page = create(hop, "Team page");
        long id = page.summary().id();
        saveWithGroup(hop, page, IntNode.valueOf(40));
        store.groupMembers.get(40L).add(3L); // mallory joins the group herself

        assertEquals(Outcome.NOT_FOUND, service.open(mallory, id).outcome(), "joining grants nothing yet");
        assertEquals(Outcome.FORBIDDEN, service.refreshEditors(ada, id).outcome());
        assertEquals(Outcome.OK, service.refreshEditors(hop, id).outcome());
        assertEquals(List.of(2L, 3L), store.editorsOf(id));
        assertEquals(Outcome.OK, service.open(mallory, id).outcome());

        List<PageSummary> adaPages = service.list(ada, "mine", null).body();
        assertEquals(1, adaPages.size());
        assertFalse(adaPages.get(0).isOwner());
        assertEquals("Hop Student", adaPages.get(0).ownerName());
    }

    @Test
    void copyingAGroupsMembersChangesOnlyTheEditorRowsThatDiffer() {
        PageDetail page = create(hop, "Team page");
        long id = page.summary().id();
        saveWithGroup(hop, page, IntNode.valueOf(40));
        Long adaRow = store.editorRowId(id, 2);
        verify(store.editors, never()).deleteAllByIdInBatch(anyIterable()); // a page's first team deletes nothing

        store.groupMembers.get(40L).add(3L);
        service.refreshEditors(hop, id);
        assertEquals(List.of(2L, 3L), store.editorsOf(id));
        assertEquals(adaRow, store.editorRowId(id, 2), "ada's row is kept, not deleted and inserted again");

        store.groupMembers.get(40L).remove(2L);
        service.refreshEditors(hop, id);
        assertEquals(List.of(3L), store.editorsOf(id));
        verify(store.editors, never()).deleteByPageId(anyLong());
    }

    @Test
    void chatAndMembersBlocksNeedAGroupTheEditorBelongsTo() {
        ObjectNode spec = store.fixture("all-blocks.json");

        Result<PageDetail> notMember = service.create(mallory, new CreateRequest(null, spec, null, null, null, null, null));
        assertEquals(Outcome.INVALID_SPEC, notMember.outcome());
        assertEquals(List.of(new Issue("b-members01", "group", "group_not_allowed"),
                new Issue("b-chat01", "group", "group_not_allowed")), notMember.details().get("details"));

        store.group(42, "Binary Beasts P3", 1L, 2L);
        PageDetail page = create(hop, store.fixture("all-blocks.json"));
        assertEquals("Binary Beasts P3", page.spec().at("/sections/3/cells/1/blocks/0/settings/group/name").asText(),
                "Spring writes its own group name");

        ObjectNode classChat = store.fixture("all-blocks.json");
        ((ObjectNode) classChat.at("/sections/3/cells/1/blocks/0/settings/group")).put("id", 50);
        Result<PageDetail> classGroup = save(niko, page, classChat);
        assertEquals(Outcome.INVALID_SPEC, classGroup.outcome(), "not even a teacher can embed a class group's chat");
    }

    @Test
    void aGroupAlreadyOnThePageDoesntStopAnotherEditorFromSaving() {
        store.group(42, "Binary Beasts", 1L);
        PageDetail page = create(hop, store.fixture("all-blocks.json"));
        PageDetail grouped = saveWithGroup(hop, page, IntNode.valueOf(40)).body();

        Result<PageDetail> adaSave = save(ada, grouped, (ObjectNode) grouped.spec());
        assertEquals(Outcome.OK, adaSave.outcome(), "ada isn't in group 42, but hop put it there");

        ObjectNode newRef = (ObjectNode) adaSave.body().spec().deepCopy();
        ((ObjectNode) newRef.at("/sections/3/cells/1/blocks/0/settings/group")).put("id", 41);
        assertEquals(Outcome.INVALID_SPEC, save(ada, adaSave.body(), newRef).outcome(), "a new group needs a member");
    }

    @Test
    void myGroupsLeavesOutClassGroupsAndCourseChannels() {
        assertEquals(List.of("Binary Beasts"), service.myGroups(hop).body().stream()
                .map(UiBuilderPageApiRestController.GroupOption::name).toList());
    }

    @Test
    void membersAreListedOnlyForAPublishedMembersBlock() {
        store.group(42, "Binary Beasts", 1L, 2L);
        publish(hop, create(hop, store.fixture("all-blocks.json")), "flood-watch");

        assertEquals(List.of(new MemberName("Ada Student"), new MemberName("Hop Student")),
                service.members(mallory, "flood-watch", "b-members01").body());
        assertEquals(Outcome.NOT_FOUND, service.members(mallory, "flood-watch", "b-chat01").outcome());
        assertEquals(Outcome.NOT_FOUND, service.members(mallory, "nope-page", "b-members01").outcome());

        PublishedView view = service.published(ada, "flood-watch").body();
        assertTrue(view.groups().get("42").viewerIsMember());
        assertFalse(service.published(mallory, "flood-watch").body().groups().get("42").viewerIsMember());
        assertEquals("Binary Beasts", service.published(mallory, "flood-watch").body().groups().get("42").name());
    }

    @Test
    void messagingTheOwnerOpensOneConversationButNotWithYourself() {
        publish(hop, create(hop, "Flood Watch"), "flood-watch");

        assertEquals(Outcome.OWN_PAGE, service.messageOwner(hop, "flood-watch").outcome());
        verify(store.directMessages, never()).getOrCreateConversation(any(), anyList());
        Long first = service.messageOwner(ada, "flood-watch").body().conversationId();
        assertEquals(first, service.messageOwner(ada, "flood-watch").body().conversationId());
        assertEquals(List.of(List.of(2L, 1L)), store.conversations);
        assertEquals(Outcome.NOT_FOUND, service.messageOwner(ada, "draft-page").outcome());
    }

    // ---- leases ----

    @Test
    void theLeaseIsTakenRenewedHeldExpiredTakenOverAndReleased() {
        PageDetail page = saveWithGroup(hop, create(hop, "Team page"), IntNode.valueOf(40)).body();
        long id = page.summary().id();
        long now = UiBuilderTestStore.NOW.toEpochMilli();

        assertEquals(new LeaseView("Hop Student", now + 60_000, true), service.lease(hop, id, false).body());
        assertEquals(new LeaseView("Hop Student", now + 60_000, false), service.lease(ada, id, false).body());
        PageSummary seenByAda = service.open(ada, id).body().summary();
        assertEquals(new LeaseView("Hop Student", now + 60_000, false), seenByAda.lease());

        store.clock.advance(Duration.ofSeconds(30));
        assertEquals(now + 90_000, service.lease(hop, id, false).body().expiresAt(), "each call renews it");

        assertEquals(new LeaseView("Ada Student", now + 90_000, true), service.lease(ada, id, true).body(), "take over");
        assertEquals(Outcome.NO_CONTENT, service.releaseLease(hop, id).outcome());
        assertEquals("Ada Student", store.stored(id).getLeaseName(), "releasing someone else's lease does nothing");
        service.releaseLease(ada, id);
        assertNull(service.open(hop, id).body().summary().lease());

        service.lease(ada, id, false);
        store.clock.advance(Duration.ofSeconds(61));
        assertTrue(service.lease(hop, id, false).body().mine(), "an expired lease is free");
    }

    // ---- revisions ----

    @Test
    void savesWithinTenMinutesByOnePersonShareARevision() {
        PageDetail page = create(hop, "v0");
        long id = page.summary().id();
        PageDetail current = page;
        for (int i = 1; i <= 3; i++) {
            store.clock.advance(Duration.ofMinutes(3));
            current = save(hop, current, store.simplePage("v" + i)).body();
        }
        List<UiBuilderPageRevisionEntity> rows = store.revisionsOf(id);
        assertEquals(1, rows.size(), "the create and three saves within nine minutes");
        assertEquals(3L, rows.get(0).getPageVersion());
        assertEquals(UiBuilderTestStore.NOW.toEpochMilli(), rows.get(0).getSavedAt(), "when the revision was started");
        assertTrue(rows.get(0).getSpec().contains("\"v3\""));

        store.clock.advance(Duration.ofMinutes(2));
        current = save(hop, current, store.simplePage("v4")).body();
        assertEquals(2, store.revisionsOf(id).size(), "ten minutes after the revision started");

        current = saveWithGroup(hop, current, IntNode.valueOf(40)).body();
        current = save(ada, current, store.simplePage("Ada's")).body();
        assertEquals(List.of("Ada Student", "Hop Student", "Hop Student"),
                store.revisionsOf(id).stream().map(UiBuilderPageRevisionEntity::getSavedByName).toList(),
                "another person's save starts a revision, so theirs is kept");
    }

    @Test
    void keepMineAfterASamePersonConflictKeepsBothCopiesInRevisions() {
        PageDetail page = create(hop, "v0");
        long id = page.summary().id();
        store.clock.advance(Duration.ofMinutes(1));
        save(hop, page, store.simplePage("Tab A")); // goes into the create's revision: same person, same run
        Result<PageDetail> tabB = save(hop, page, store.simplePage("Tab B")); // the other tab, still at version 0
        assertEquals(Outcome.VERSION_CONFLICT, tabB.outcome());

        long current = (Long) tabB.details().get("currentVersion");
        PageDetail kept = service.save(hop, id, new SaveRequest(current, store.simplePage("Tab B"), null, null, null,
                null, null, null, true)).body();

        List<UiBuilderPageRevisionEntity> rows = store.revisionsOf(id);
        assertEquals(List.of(2L, 1L), rows.stream().map(UiBuilderPageRevisionEntity::getPageVersion).toList());
        assertTrue(rows.get(0).getSpec().contains("\"Tab B\""), "keep mine is a revision of its own");
        assertTrue(rows.get(1).getSpec().contains("\"Tab A\""), "theirs stays in Revisions");

        save(hop, kept, store.simplePage("Tab B, later"));
        assertEquals(2, store.revisionsOf(id).size(), "that tab's next saves go into its own revision");
        assertTrue(store.revisionsOf(id).get(1).getSpec().contains("\"Tab A\""));
    }

    @Test
    void revisionsKeepThirtySavesEveryPublishAndRestoreAndFiftyInAll() {
        PageDetail current = create(hop, "v0");
        long id = current.summary().id();
        current = publish(hop, current, "flood-watch");
        for (int i = 1; i <= 40; i++) {
            store.clock.advance(Duration.ofMinutes(11));
            current = save(hop, current, store.simplePage("v" + i)).body();
        }
        List<UiBuilderPageRevisionEntity> rows = store.revisionsOf(id);
        assertEquals(31, rows.size());
        assertEquals(30, rows.stream().filter(row -> "SAVE".equals(row.getKind())).count());
        assertEquals("PUBLISH", rows.get(rows.size() - 1).getKind(), "older than every save kept, but kept");

        for (int i = 0; i < 25; i++) {
            current = publish(hop, current, null);
        }
        rows = store.revisionsOf(id);
        assertEquals(50, rows.size(), "the cap removes the oldest rows of any kind");
        assertEquals(25, rows.stream().filter(row -> "PUBLISH".equals(row.getKind())).count());
        assertEquals(25, rows.stream().filter(row -> "SAVE".equals(row.getKind())).count());
        assertEquals("SAVE", rows.get(rows.size() - 1).getKind(), "the first publish was the oldest, so it went");
    }

    @Test
    void restoringMakesAnOldCopyTheNextDraftVersionWithoutPublishing() {
        PageDetail page = publish(hop, create(hop, "Original"), "flood-watch");
        store.clock.advance(Duration.ofMinutes(11));
        PageDetail changed = save(hop, page, store.simplePage("Broken")).body();
        List<RevisionSummary> history = service.revisions(hop, page.summary().id()).body();
        assertEquals(List.of("SAVE", "PUBLISH", "SAVE"), history.stream().map(RevisionSummary::kind).toList());
        long original = history.get(2).revisionId();

        assertEquals(Outcome.VERSION_CONFLICT, service.restore(hop, page.summary().id(), original,
                new RestoreRequest(0L)).outcome());
        assertEquals(Outcome.NOT_FOUND, service.restore(hop, page.summary().id(), 999,
                new RestoreRequest(changed.summary().version())).outcome());
        PageDetail restored = service.restore(hop, page.summary().id(), original,
                new RestoreRequest(changed.summary().version())).body();

        assertEquals(2, restored.summary().version());
        assertEquals("Original", restored.summary().title());
        assertEquals("Original", restored.spec().at("/page/title").asText());
        assertEquals(0L, restored.summary().publishedVersion(), "never publishes");
        assertEquals("RESTORE", service.revisions(hop, page.summary().id()).body().get(0).kind());
        assertEquals("Original", service.revision(hop, page.summary().id(), original).body().spec().at("/page/title").asText());
        assertEquals(Outcome.NOT_FOUND, service.revision(mallory, page.summary().id(), original).outcome());
    }

    // ---- lists ----

    @Test
    void teachersListAClassAndStudentsCant() {
        create(hop, "One");
        create(ada, "Two");
        Result<PageDetail> csa = service.create(ada, new CreateRequest(null, store.simplePage("Three"), null, null,
                "csa", null, null));
        assertEquals(Outcome.CREATED, csa.outcome());

        assertEquals(Outcome.FORBIDDEN, service.list(hop, "class", "csp").outcome());
        assertEquals(Outcome.INVALID_SCOPE, service.list(hop, "everything", null).outcome());
        assertEquals(Outcome.INVALID_COURSE, service.list(niko, "class", "art").outcome());
        assertEquals(List.of("Two", "One"), service.list(niko, "class", "csp").body().stream().map(PageSummary::title).toList());
        assertEquals(3, service.list(niko, "class", "").body().size());
        assertEquals(List.of("One"), service.list(hop, "mine", null).body().stream().map(PageSummary::title).toList());
    }

    @Test
    void slugAvailabilitySaysWhyNot() {
        publish(hop, create(hop, "Flood Watch"), "flood-watch");

        assertTrue(service.slugAvailable("river-watch").body().available());
        assertEquals("taken", service.slugAvailable("flood-watch").body().reason());
        assertEquals("reserved", service.slugAvailable("admin").body().reason());
        assertEquals("invalid", service.slugAvailable("No Spaces").body().reason());
        assertEquals("invalid", service.slugAvailable(null).body().reason());
        assertEquals("flood-watch", service.slugAvailable("  flood-watch ").body().slug());
    }
}
