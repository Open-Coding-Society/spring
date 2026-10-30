package com.open.spring.mvc.uibuilder;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Limit;
import org.springframework.data.projection.ProjectionFactory;
import org.springframework.data.projection.SpelAwareProxyProjectionFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.open.spring.mvc.directmessages.DirectMessageConversation;
import com.open.spring.mvc.directmessages.DirectMessageService;
import com.open.spring.mvc.groups.CourseGroupProperties;
import com.open.spring.mvc.groups.Groups;
import com.open.spring.mvc.groups.GroupsJpaRepository;
import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;
import com.open.spring.mvc.uibuilder.UiBuilderPageApiRestController.BulkResult;
import com.open.spring.mvc.uibuilder.UiBuilderPageApiRestController.BulkVisibilityRequest;
import com.open.spring.mvc.uibuilder.UiBuilderPageApiRestController.CreateRequest;
import com.open.spring.mvc.uibuilder.UiBuilderPageApiRestController.GroupOption;
import com.open.spring.mvc.uibuilder.UiBuilderPageApiRestController.LeaseView;
import com.open.spring.mvc.uibuilder.UiBuilderPageApiRestController.PageDetail;
import com.open.spring.mvc.uibuilder.UiBuilderPageApiRestController.PageSummary;
import com.open.spring.mvc.uibuilder.UiBuilderPageApiRestController.PublishRequest;
import com.open.spring.mvc.uibuilder.UiBuilderPageApiRestController.RestoreRequest;
import com.open.spring.mvc.uibuilder.UiBuilderPageApiRestController.RevisionDetail;
import com.open.spring.mvc.uibuilder.UiBuilderPageApiRestController.RevisionSummary;
import com.open.spring.mvc.uibuilder.UiBuilderPageApiRestController.SaveRequest;
import com.open.spring.mvc.uibuilder.UiBuilderPageApiRestController.SlugAvailability;
import com.open.spring.mvc.uibuilder.UiBuilderPageApiRestController.VisibilityRequest;
import com.open.spring.mvc.uibuilder.UiBuilderPageJpaRepository.NameRow;
import com.open.spring.mvc.uibuilder.UiBuilderPageJpaRepository.PageRow;
import com.open.spring.mvc.uibuilder.UiBuilderPublishedApiRestController.ConversationRef;
import com.open.spring.mvc.uibuilder.UiBuilderPublishedApiRestController.GalleryItem;
import com.open.spring.mvc.uibuilder.UiBuilderPublishedApiRestController.GroupView;
import com.open.spring.mvc.uibuilder.UiBuilderPublishedApiRestController.MadeBy;
import com.open.spring.mvc.uibuilder.UiBuilderPublishedApiRestController.MemberName;
import com.open.spring.mvc.uibuilder.UiBuilderPublishedApiRestController.PublishedView;
import com.open.spring.mvc.uibuilder.UiBuilderPublishedApiRestController.Viewer;
import com.open.spring.mvc.uibuilder.UiBuilderSpecRules.GroupRef;
import com.open.spring.mvc.uibuilder.UiBuilderSpecRules.Issue;
import com.open.spring.mvc.uibuilder.UiBuilderSpecRules.Mode;
import com.open.spring.mvc.uibuilder.UiBuilderSpecRules.PageFields;

/**
 * The rules for UI Builder pages: who may edit, save, publish and see a page, and what is kept.
 *
 * Editors are the owner, teachers and admins, and the people copied from the page's group into
 * ui_builder_page_editor when its owner or a teacher sets or refreshes the group. Group membership
 * is never checked at request time, so joining a group grants nothing until a refresh. Anyone who
 * can't edit a page gets NOT_FOUND for it, never FORBIDDEN, so ids don't reveal which pages exist.
 *
 * Every write is one transaction whose first statement takes the page's row lock
 * (UiBuilderPageJpaRepository.lockForWrite) before anything is read: SQLite has no SELECT ... FOR
 * UPDATE, and a transaction that reads first fails with SQLITE_BUSY if another write commits in
 * between. Then it reads, checks access, compares the editor's draft version (VERSION_CONFLICT on a
 * mismatch), writes and records a revision. A refused write is rolled back. Methods return a typed
 * Result; UiBuilderException.statusOf is the only place an outcome becomes an HTTP status.
 *
 * Publishing copies the draft without its hidden nodes to the published copy, which signed-in
 * members see. A teacher's approval copies the published copy to the public copy, which anonymous
 * visitors see, and only when it is the published version the teacher reviewed; later publishes
 * don't reach them until a teacher approves again. Each copy also keeps a small gallery card.
 */
@Service
public class UiBuilderPageService {

    static final long LEASE_MILLIS = 60_000;
    /** Saves by one person within ten minutes of the newest revision go into that revision. */
    static final long SAVE_GROUP_MILLIS = 10 * 60_000;
    static final int KEEP_SAVES = 30;
    static final int KEEP_REVISIONS = 50;

    private static final Logger logger = LoggerFactory.getLogger(UiBuilderPageService.class);
    private static final ProjectionFactory PROJECTIONS = new SpelAwareProxyProjectionFactory();
    private static final Comparator<PageRow> NEWEST_FIRST = Comparator
            .comparing(PageRow::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(PageRow::getId, Comparator.reverseOrder());

    /** What a service call did. Each failure's error code is its name in lower case, such as version_conflict. */
    public enum Outcome {
        OK, CREATED, NO_CONTENT,
        NOT_FOUND, FORBIDDEN, VERSION_CONFLICT,
        INVALID_SPEC, INVALID_GROUP, INVALID_VISIBILITY, INVALID_UNTIL, INVALID_COURSE, INVALID_SCOPE,
        SLUG_REQUIRED, INVALID_SLUG, SLUG_RESERVED, SLUG_TAKEN, SLUG_LOCKED,
        LOOPBACK_SOURCE, NOT_PUBLISHED, PUBLISHED_CHANGED, OWN_PAGE, TOO_MANY_PAGES
    }

    /**
     * A service result: the outcome, the response body when it succeeded, and extra error fields
     * (such as currentVersion, or the spec problems under "details") when it didn't.
     */
    public record Result<T>(Outcome outcome, T body, Map<String, Object> details) {
        static <T> Result<T> ok(T body) {
            return new Result<>(Outcome.OK, body, Map.of());
        }

        static <T> Result<T> created(T body) {
            return new Result<>(Outcome.CREATED, body, Map.of());
        }

        static <T> Result<T> noContent() {
            return new Result<>(Outcome.NO_CONTENT, null, Map.of());
        }

        static <T> Result<T> refused(Outcome outcome) {
            return new Result<>(outcome, null, Map.of());
        }

        static <T> Result<T> refused(Outcome outcome, Map<String, Object> details) {
            return new Result<>(outcome, null, details);
        }

        public boolean succeeded() {
            return outcome == Outcome.OK || outcome == Outcome.CREATED || outcome == Outcome.NO_CONTENT;
        }
    }

    /** The signed-in account making a request; teacher is true for ROLE_TEACHER and ROLE_ADMIN. */
    public record Caller(long personId, String uid, String name, boolean teacher) {}

    /** What a request says about the page's group: nothing (keep it), no group, or a group id. */
    private record GroupChange(boolean present, Long groupId, boolean invalid) {}

    private final UiBuilderPageJpaRepository pages;
    private final UiBuilderPageRevisionJpaRepository revisions;
    private final UiBuilderPageEditorJpaRepository editors;
    private final PersonJpaRepository people;
    private final GroupsJpaRepository groups;
    private final DirectMessageService directMessages;
    private final CourseGroupProperties courseGroups;
    private final UiBuilderSpecRules rules;
    private final UiBuilderLimits limits;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public UiBuilderPageService(UiBuilderPageJpaRepository pages, UiBuilderPageRevisionJpaRepository revisions,
            UiBuilderPageEditorJpaRepository editors, PersonJpaRepository people, GroupsJpaRepository groups,
            DirectMessageService directMessages, CourseGroupProperties courseGroups, UiBuilderSpecRules rules,
            UiBuilderLimits limits, ObjectMapper objectMapper) {
        this(pages, revisions, editors, people, groups, directMessages, courseGroups, rules, limits, objectMapper,
                Clock.systemUTC());
    }

    UiBuilderPageService(UiBuilderPageJpaRepository pages, UiBuilderPageRevisionJpaRepository revisions,
            UiBuilderPageEditorJpaRepository editors, PersonJpaRepository people, GroupsJpaRepository groups,
            DirectMessageService directMessages, CourseGroupProperties courseGroups, UiBuilderSpecRules rules,
            UiBuilderLimits limits, ObjectMapper objectMapper, Clock clock) {
        this.pages = pages;
        this.revisions = revisions;
        this.editors = editors;
        this.people = people;
        this.groups = groups;
        this.directMessages = directMessages;
        this.courseGroups = courseGroups;
        this.rules = rules;
        this.limits = limits;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** The account behind a request, or empty for an anonymous one (or a login whose account is gone). */
    public Optional<Caller> caller(Authentication authentication) {
        if (authentication == null || authentication instanceof AnonymousAuthenticationToken
                || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        Person person = people.findByUid(authentication.getName());
        if (person == null || person.getId() == null) {
            return Optional.empty();
        }
        boolean teacher = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(role -> "ROLE_TEACHER".equals(role) || "ROLE_ADMIN".equals(role));
        return Optional.of(new Caller(person.getId(), person.getUid(), person.getName(), teacher));
    }

    // ---- lists ----

    /** scope "mine": pages the caller owns or edits; scope "class": a teacher's view of every page (of a course). */
    @Transactional(readOnly = true)
    public Result<List<PageSummary>> list(Caller caller, String scope, String course) {
        if ("mine".equals(scope)) {
            return Result.ok(summaries(minePages(caller), caller));
        }
        if (!"class".equals(scope)) {
            return Result.refused(Outcome.INVALID_SCOPE);
        }
        if (!caller.teacher()) {
            return Result.refused(Outcome.FORBIDDEN);
        }
        Limit max = Limit.of(limits.getClassPagesMax());
        String code = course == null ? "" : course.strip();
        if (code.isEmpty()) {
            return Result.ok(summaries(pages.findByDeletedAtIsNullOrderByUpdatedAtDescIdDesc(max), caller));
        }
        if (!courseCodes().contains(code)) {
            return Result.refused(Outcome.INVALID_COURSE);
        }
        return Result.ok(summaries(pages.findByCourseAndDeletedAtIsNullOrderByUpdatedAtDescIdDesc(code, max), caller));
    }

    private List<PageRow> minePages(Caller caller) {
        Map<Long, PageRow> rows = new LinkedHashMap<>();
        pages.findByOwnerPersonIdAndDeletedAtIsNull(caller.personId()).forEach(row -> rows.put(row.getId(), row));
        List<Long> shared = editors.findByPersonId(caller.personId()).stream()
                .map(UiBuilderPageEditorEntity::getPageId)
                .filter(id -> !rows.containsKey(id))
                .distinct()
                .toList();
        if (!shared.isEmpty()) {
            pages.findByIdInAndDeletedAtIsNull(shared).forEach(row -> rows.put(row.getId(), row));
        }
        return rows.values().stream().sorted(NEWEST_FIRST).toList();
    }

    // ---- one page ----

    @Transactional(readOnly = true)
    public Result<PageDetail> open(Caller caller, long id) {
        Optional<UiBuilderPageEntity> page = pages.findByIdAndDeletedAtIsNull(id);
        if (page.isEmpty() || !canEdit(page.get(), caller)) {
            return Result.refused(Outcome.NOT_FOUND);
        }
        return Result.ok(detail(page.get(), caller));
    }

    /**
     * A new draft at version 0, owned by the caller, who may own at most limits.ownerPagesMax pages.
     * The row is inserted before anything is read (the page count, any group), so the transaction's
     * first statement is a write (see the class comment).
     */
    @Transactional
    public Result<PageDetail> create(Caller caller, CreateRequest request) {
        ObjectNode spec = objectOrNull(request.spec());
        List<Issue> issues = spec == null ? List.of(new Issue(null, null, "not_a_page")) : rules.validate(spec, Mode.DRAFT);
        if (!issues.isEmpty()) {
            return refuse(Outcome.INVALID_SPEC, Map.of("details", issues));
        }
        PageFields fields = effectiveFields(requestFields(request.title(), request.description(), request.byline(),
                request.course(), request.image()), rules.pageFieldsOf(spec), null);
        issues = rules.pageFieldIssues(fields, courseCodes());
        if (!issues.isEmpty()) {
            return refuse(Outcome.INVALID_SPEC, Map.of("details", issues));
        }
        GroupChange group = groupChange(request.groupId());
        if (group.invalid()) {
            return refuse(Outcome.INVALID_GROUP);
        }

        long now = clock.millis();
        writePageFields(spec, fields);
        UiBuilderPageEntity page = new UiBuilderPageEntity();
        page.setOwnerPersonId(caller.personId());
        applyFields(page, fields);
        page.setDraftVersion(0L);
        page.setDraftSpec(write(spec));
        page.setCreatedAt(now);
        page.setUpdatedAt(now);
        page.setUpdatedByName(caller.name());
        page = pages.save(page);
        // Counted after the insert, so the count includes this page.
        if (pages.countByOwnerPersonIdAndDeletedAtIsNull(caller.personId()) > limits.getOwnerPagesMax()) {
            return refuse(Outcome.TOO_MANY_PAGES, Map.of("max", limits.getOwnerPagesMax()));
        }

        List<Long> members = List.of();
        if (group.groupId() != null) {
            Optional<List<Long>> allowed = pageGroupMembers(caller, group.groupId());
            if (allowed.isEmpty()) {
                return refuse(Outcome.INVALID_GROUP);
            }
            members = allowed.get();
            page.setGroupId(group.groupId());
        }
        issues = checkGroupRefs(caller, spec, Set.of());
        if (!issues.isEmpty()) {
            return refuse(Outcome.INVALID_SPEC, Map.of("details", issues));
        }
        page.setDraftSpec(write(spec)); // group names as Spring has them
        page = pages.save(page);
        if (page.getGroupId() != null) {
            copyEditors(page, members);
        }
        recordSave(page, caller, now, false);
        return Result.created(detail(page, caller));
    }

    /**
     * Saves the draft the editor changed from version request.version. request.newRevision keeps
     * the save in a revision of its own (see recordSave).
     */
    @Transactional
    public Result<PageDetail> save(Caller caller, long id, SaveRequest request) {
        Optional<UiBuilderPageEntity> found = lockedPage(id, caller);
        if (found.isEmpty()) {
            return refuse(Outcome.NOT_FOUND);
        }
        UiBuilderPageEntity page = found.get();
        if (request.version() == null) {
            return refuse(Outcome.INVALID_SPEC, Map.of("details", List.of(new Issue(null, "version", "version_required"))));
        }
        if (request.version() != page.getDraftVersion().longValue()) {
            return conflict(page);
        }
        ObjectNode spec = objectOrNull(request.spec());
        List<Issue> issues = spec == null ? List.of(new Issue(null, null, "not_a_page")) : rules.validate(spec, Mode.DRAFT);
        if (!issues.isEmpty()) {
            return refuse(Outcome.INVALID_SPEC, Map.of("details", issues));
        }
        PageFields fields = effectiveFields(requestFields(request.title(), request.description(), request.byline(),
                request.course(), request.image()), rules.pageFieldsOf(spec), columnsOf(page));
        issues = rules.pageFieldIssues(fields, courseCodes());
        if (!issues.isEmpty()) {
            return refuse(Outcome.INVALID_SPEC, Map.of("details", issues));
        }

        GroupChange group = groupChange(request.groupId());
        if (group.invalid()) {
            return refuse(Outcome.INVALID_GROUP);
        }
        boolean groupChanges = group.present() && !Objects.equals(group.groupId(), page.getGroupId());
        if (groupChanges && !(isOwner(page, caller) || caller.teacher())) {
            return refuse(Outcome.FORBIDDEN);
        }
        List<Long> members = List.of();
        if (groupChanges && group.groupId() != null) {
            Optional<List<Long>> allowed = pageGroupMembers(caller, group.groupId());
            if (allowed.isEmpty()) {
                return refuse(Outcome.INVALID_GROUP);
            }
            members = allowed.get();
        }
        // Groups already on the stored draft were checked when someone added them.
        issues = checkGroupRefs(caller, spec, groupIdsIn(read(page.getDraftSpec())));
        if (!issues.isEmpty()) {
            return refuse(Outcome.INVALID_SPEC, Map.of("details", issues));
        }

        long now = clock.millis();
        writePageFields(spec, fields);
        applyFields(page, fields);
        page.setDraftSpec(write(spec));
        page.setDraftVersion(page.getDraftVersion() + 1);
        page.setUpdatedAt(now);
        page.setUpdatedByName(caller.name());
        if (groupChanges) {
            page.setGroupId(group.groupId());
        }
        page = pages.save(page);
        if (groupChanges) {
            copyEditors(page, members);
        }
        recordSave(page, caller, now, Boolean.TRUE.equals(request.newRevision()));
        return Result.ok(detail(page, caller));
    }

    /**
     * Publishes version request.version of the draft: its copy without hidden nodes becomes the
     * published copy. The first publish needs a slug, which is then fixed for good.
     */
    @Transactional
    public Result<PageDetail> publish(Caller caller, long id, PublishRequest request) {
        Optional<UiBuilderPageEntity> found = lockedPage(id, caller);
        if (found.isEmpty()) {
            return refuse(Outcome.NOT_FOUND);
        }
        UiBuilderPageEntity page = found.get();
        if (request.version() == null) {
            return refuse(Outcome.INVALID_SPEC, Map.of("details", List.of(new Issue(null, "version", "version_required"))));
        }
        if (request.version() != page.getDraftVersion().longValue()) {
            return conflict(page);
        }
        ObjectNode draft = objectOrNull(read(page.getDraftSpec()));
        List<Issue> issues = draft == null ? List.of(new Issue(null, null, "not_a_page")) : rules.validate(draft, Mode.DRAFT);
        if (!issues.isEmpty()) {
            return refuse(Outcome.INVALID_SPEC, Map.of("details", issues));
        }
        List<Issue> loopback = rules.validate(draft, Mode.PUBLISH).stream()
                .filter(issue -> UiBuilderSpecRules.LOOPBACK_URL.equals(issue.key()))
                .toList();
        if (!loopback.isEmpty()) {
            return refuse(Outcome.LOOPBACK_SOURCE, Map.of("details", loopback));
        }

        String requested = request.slug() == null || request.slug().isBlank() ? null : request.slug().strip();
        String slug = page.getSlug();
        if (slug == null) {
            if (requested == null) {
                return refuse(Outcome.SLUG_REQUIRED);
            }
            String problem = rules.slugIssue(requested);
            if ("invalid".equals(problem)) {
                return refuse(Outcome.INVALID_SLUG);
            }
            if ("reserved".equals(problem)) {
                return refuse(Outcome.SLUG_RESERVED);
            }
            if (pages.existsBySlug(requested)) {
                return refuse(Outcome.SLUG_TAKEN);
            }
            slug = requested;
        } else if (requested != null && !requested.equals(slug)) {
            return refuse(Outcome.SLUG_LOCKED);
        }

        ObjectNode published = rules.stripHidden(draft);
        // Every group on the draft was checked when it was saved; this checks they still exist.
        issues = checkGroupRefs(caller, published, groupIdsIn(draft));
        if (!issues.isEmpty()) {
            return refuse(Outcome.INVALID_SPEC, Map.of("details", issues));
        }

        long now = clock.millis();
        page.setSlug(slug);
        page.setStatus(UiBuilderPageEntity.PUBLISHED);
        page.setPublishedSpec(write(published));
        page.setPublishedCard(card(rules.pageFieldsOf(published)));
        page.setPublishedVersion(page.getDraftVersion());
        page.setPublishedAt(now);
        page = pages.save(page);
        addRevision(page, UiBuilderPageRevisionEntity.PUBLISH, write(draft), caller, now);
        prune(page.getId());
        return Result.ok(detail(page, caller));
    }

    /** Takes the page down; any editor may, and a teacher any page. A teacher's PUBLIC approval goes with it. */
    @Transactional
    public Result<PageDetail> unpublish(Caller caller, long id) {
        Optional<UiBuilderPageEntity> found = lockedPage(id, caller);
        if (found.isEmpty()) {
            return refuse(Outcome.NOT_FOUND);
        }
        UiBuilderPageEntity page = found.get();
        boolean wasPublic = UiBuilderPageEntity.PUBLIC.equals(page.getVisibility());
        page.setStatus(UiBuilderPageEntity.DRAFT);
        page.setPublishedSpec(null);
        page.setPublishedCard(null);
        page.setPublishedVersion(null);
        page.setPublishedAt(null);
        clearPublic(page);
        page = pages.save(page);
        if (wasPublic) {
            // Root logging is at warn in production, and visibility changes must reach the log.
            logger.warn("UI Builder page {} unpublished by {}: it is no longer public", page.getId(), caller.uid());
        }
        return Result.ok(detail(page, caller));
    }

    /**
     * PUBLIC from a teacher approves the published copy for everyone (until request.until, if given),
     * but only the version the teacher reviewed: request.publishedVersion must be the published
     * version, else PUBLISHED_CHANGED with the current one. A page that isn't published, never or not
     * since the review, is NOT_PUBLISHED whatever version is named.
     * PUBLIC from any other editor only asks for it. SIGNED_IN clears the approval and the request.
     */
    @Transactional
    public Result<PageDetail> setVisibility(Caller caller, long id, VisibilityRequest request) {
        Optional<UiBuilderPageEntity> found = lockedPage(id, caller);
        if (found.isEmpty()) {
            return refuse(Outcome.NOT_FOUND);
        }
        UiBuilderPageEntity page = found.get();
        String visibility = request.visibility();
        if (!UiBuilderPageEntity.PUBLIC.equals(visibility) && !UiBuilderPageEntity.SIGNED_IN.equals(visibility)) {
            return refuse(Outcome.INVALID_VISIBILITY);
        }
        long now = clock.millis();
        if (request.until() != null && request.until() <= now) {
            return refuse(Outcome.INVALID_UNTIL);
        }
        if (UiBuilderPageEntity.SIGNED_IN.equals(visibility)) {
            clearPublic(page);
            logger.warn("UI Builder page {} made members-only by {}", page.getId(), caller.uid());
        } else if (caller.teacher()) {
            // Before the versions: a page unpublished since the review has no version to compare, and
            // published_changed would tell the teacher it was published again.
            if (!UiBuilderPageEntity.PUBLISHED.equals(page.getStatus()) || page.getPublishedSpec() == null) {
                return refuse(Outcome.NOT_PUBLISHED);
            }
            // A publish between the teacher's review and this click must not reach visitors unseen.
            if (!Objects.equals(request.publishedVersion(), page.getPublishedVersion())) {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("publishedVersion", page.getPublishedVersion());
                return refuse(Outcome.PUBLISHED_CHANGED, details);
            }
            page.setVisibility(UiBuilderPageEntity.PUBLIC);
            page.setPublicSpec(page.getPublishedSpec());
            page.setPublicCard(page.getPublishedCard() != null ? page.getPublishedCard()
                    : card(rules.pageFieldsOf(page.getPublishedSpec())));
            page.setPublicVersion(page.getPublishedVersion());
            page.setPublicRequested(false);
            page.setPublicUntil(request.until());
            logger.warn("UI Builder page {} version {} approved for everyone by {} until {}", page.getId(),
                    page.getPublishedVersion(), caller.uid(), request.until());
        } else {
            page.setPublicRequested(true);
            logger.warn("UI Builder page {}: {} asked for it to be public", page.getId(), caller.uid());
        }
        page = pages.save(page);
        return Result.ok(detail(page, caller));
    }

    /** A teacher approves the current published copy of every published page of a course for everyone. */
    @Transactional
    public Result<BulkResult> approveCourse(Caller caller, BulkVisibilityRequest request) {
        if (!caller.teacher()) {
            return refuse(Outcome.FORBIDDEN);
        }
        String course = request.course() == null ? "" : request.course().strip();
        if (!courseCodes().contains(course)) {
            return refuse(Outcome.INVALID_COURSE);
        }
        if (request.until() != null && request.until() <= clock.millis()) {
            return refuse(Outcome.INVALID_UNTIL);
        }
        // One UPDATE, which takes its own lock; nothing is read first.
        int updated = pages.approveCourse(course, request.until());
        logger.warn("UI Builder: {} published {} pages approved for everyone by {} until {}", updated, course,
                caller.uid(), request.until());
        return Result.ok(new BulkResult(updated));
    }

    /**
     * Soft delete by the owner or a teacher: the row stays (DELETED) so its slug is never reused,
     * but its specs, revisions and editors are removed.
     */
    @Transactional
    public Result<Void> delete(Caller caller, long id) {
        Optional<UiBuilderPageEntity> found = lockedPage(id, caller);
        if (found.isEmpty()) {
            return refuse(Outcome.NOT_FOUND);
        }
        UiBuilderPageEntity page = found.get();
        boolean owner = isOwner(page, caller);
        if (!owner && !caller.teacher()) {
            return refuse(Outcome.FORBIDDEN);
        }
        page.setStatus(UiBuilderPageEntity.DELETED);
        page.setDeletedAt(clock.millis());
        page.setDraftSpec("{}");
        page.setPublishedSpec(null);
        page.setPublishedCard(null);
        page.setPublishedVersion(null);
        clearPublic(page);
        clearLease(page);
        pages.save(page);
        revisions.deleteByPageId(page.getId());
        editors.deleteByPageId(page.getId());
        if (!owner) {
            logger.warn("UI Builder page {} (\"{}\") deleted by teacher {}", page.getId(), page.getTitle(), caller.uid());
        }
        return Result.noContent();
    }

    // ---- revisions ----

    @Transactional(readOnly = true)
    public Result<List<RevisionSummary>> revisions(Caller caller, long id) {
        Optional<UiBuilderPageEntity> page = pages.findByIdAndDeletedAtIsNull(id);
        if (page.isEmpty() || !canEdit(page.get(), caller)) {
            return Result.refused(Outcome.NOT_FOUND);
        }
        return Result.ok(revisions.findByPageIdOrderByIdDesc(id).stream()
                .map(row -> new RevisionSummary(row.getId(), row.getPageVersion(), row.getKind(), row.getSavedByName(),
                        row.getSavedAt()))
                .toList());
    }

    @Transactional(readOnly = true)
    public Result<RevisionDetail> revision(Caller caller, long id, long revisionId) {
        Optional<UiBuilderPageEntity> page = pages.findByIdAndDeletedAtIsNull(id);
        if (page.isEmpty() || !canEdit(page.get(), caller)) {
            return Result.refused(Outcome.NOT_FOUND);
        }
        return revisions.findByIdAndPageId(revisionId, id)
                .map(row -> Result.ok(new RevisionDetail(row.getId(), row.getPageVersion(), row.getKind(),
                        row.getSavedByName(), row.getSavedAt(), read(row.getSpec()))))
                .orElseGet(() -> Result.refused(Outcome.NOT_FOUND));
    }

    /** Makes an earlier copy the draft again, as a new draft version. It never publishes. */
    @Transactional
    public Result<PageDetail> restore(Caller caller, long id, long revisionId, RestoreRequest request) {
        Optional<UiBuilderPageEntity> found = lockedPage(id, caller);
        if (found.isEmpty()) {
            return refuse(Outcome.NOT_FOUND);
        }
        UiBuilderPageEntity page = found.get();
        if (request.version() == null) {
            return refuse(Outcome.INVALID_SPEC, Map.of("details", List.of(new Issue(null, "version", "version_required"))));
        }
        if (request.version() != page.getDraftVersion().longValue()) {
            return conflict(page);
        }
        Optional<UiBuilderPageRevisionEntity> revision = revisions.findByIdAndPageId(revisionId, id);
        if (revision.isEmpty()) {
            return refuse(Outcome.NOT_FOUND);
        }
        ObjectNode spec = objectOrNull(read(revision.get().getSpec()));
        List<Issue> issues = spec == null ? List.of(new Issue(null, null, "not_a_page")) : rules.validate(spec, Mode.DRAFT);
        if (!issues.isEmpty()) {
            return refuse(Outcome.INVALID_SPEC, Map.of("details", issues));
        }
        PageFields fields = effectiveFields(requestFields(null, null, null, null, null), rules.pageFieldsOf(spec),
                columnsOf(page));
        issues = rules.pageFieldIssues(fields, courseCodes());
        if (!issues.isEmpty()) {
            return refuse(Outcome.INVALID_SPEC, Map.of("details", issues));
        }
        Set<Long> known = new HashSet<>(groupIdsIn(spec));
        known.addAll(groupIdsIn(read(page.getDraftSpec())));
        issues = checkGroupRefs(caller, spec, known);
        if (!issues.isEmpty()) {
            return refuse(Outcome.INVALID_SPEC, Map.of("details", issues));
        }

        long now = clock.millis();
        writePageFields(spec, fields);
        applyFields(page, fields);
        page.setDraftSpec(write(spec));
        page.setDraftVersion(page.getDraftVersion() + 1);
        page.setUpdatedAt(now);
        page.setUpdatedByName(caller.name());
        page = pages.save(page);
        addRevision(page, UiBuilderPageRevisionEntity.RESTORE, page.getDraftSpec(), caller, now);
        prune(page.getId());
        return Result.ok(detail(page, caller));
    }

    // ---- lease ----

    /**
     * The editing lease (WordPress-style post locking): taken when free, expired or already the
     * caller's, and renewed by every call for LEASE_MILLIS. force takes it from someone else. It is
     * advisory: the version check is what stops one editor overwriting another.
     */
    @Transactional
    public Result<LeaseView> lease(Caller caller, long id, boolean force) {
        Optional<UiBuilderPageEntity> found = lockedPage(id, caller);
        if (found.isEmpty()) {
            return refuse(Outcome.NOT_FOUND);
        }
        UiBuilderPageEntity page = found.get();
        long now = clock.millis();
        boolean free = page.getLeasePersonId() == null || page.getLeaseExpiresAt() == null
                || page.getLeaseExpiresAt() <= now;
        boolean mine = Objects.equals(page.getLeasePersonId(), caller.personId());
        if (!(force || free || mine)) {
            return Result.ok(new LeaseView(page.getLeaseName(), page.getLeaseExpiresAt(), false));
        }
        page.setLeasePersonId(caller.personId());
        page.setLeaseName(caller.name());
        page.setLeaseExpiresAt(now + LEASE_MILLIS);
        pages.save(page);
        return Result.ok(new LeaseView(caller.name(), now + LEASE_MILLIS, true));
    }

    /** Gives the lease back when the caller holds it; otherwise changes nothing. */
    @Transactional
    public Result<Void> releaseLease(Caller caller, long id) {
        Optional<UiBuilderPageEntity> found = lockedPage(id, caller);
        if (found.isEmpty()) {
            return refuse(Outcome.NOT_FOUND);
        }
        UiBuilderPageEntity page = found.get();
        if (Objects.equals(page.getLeasePersonId(), caller.personId())) {
            clearLease(page);
            pages.save(page);
        }
        return Result.noContent();
    }

    // ---- editors and groups ----

    /** The owner or a teacher copies the page group's current members in as its editors again. */
    @Transactional
    public Result<PageDetail> refreshEditors(Caller caller, long id) {
        Optional<UiBuilderPageEntity> found = lockedPage(id, caller);
        if (found.isEmpty()) {
            return refuse(Outcome.NOT_FOUND);
        }
        UiBuilderPageEntity page = found.get();
        if (!isOwner(page, caller) && !caller.teacher()) {
            return refuse(Outcome.FORBIDDEN);
        }
        copyEditors(page, page.getGroupId() == null ? List.of() : memberIds(page.getGroupId()));
        return Result.ok(detail(page, caller));
    }

    /** The caller's groups that a page may use, by name. */
    @Transactional(readOnly = true)
    public Result<List<GroupOption>> myGroups(Caller caller) {
        return Result.ok(groups.findGroupsByPersonId(caller.personId()).stream()
                .filter(group -> group.getName() != null && !isRestrictedGroup(group.getName()))
                .map(group -> new GroupOption(group.getId(), group.getName()))
                .distinct()
                .sorted(Comparator.comparing(GroupOption::name, String.CASE_INSENSITIVE_ORDER))
                .toList());
    }

    /** Advisory only: publishing checks the slug again inside its lock. */
    @Transactional(readOnly = true)
    public Result<SlugAvailability> slugAvailable(String slug) {
        String value = slug == null ? "" : slug.strip();
        String problem = rules.slugIssue(value);
        if (problem != null) {
            return Result.ok(new SlugAvailability(value, false, problem));
        }
        if (pages.existsBySlug(value)) {
            return Result.ok(new SlugAvailability(value, false, "taken"));
        }
        return Result.ok(new SlugAvailability(value, true, null));
    }

    // ---- published pages ----

    /**
     * Published pages, newest first, at most limits.galleryMax. Signed-in members see every one;
     * anonymous visitors see pages approved for everyone, as approved. The cards' title, description,
     * byline and picture come from the copy the viewer may open, not from the newer draft: from that
     * copy's card column, so a gallery load never reads specs. visibility is PUBLIC while visitors
     * can open the page (an approval that ran out is SIGNED_IN).
     */
    @Transactional(readOnly = true)
    public Result<List<GalleryItem>> gallery(Caller viewer, String course) {
        String filter = course == null || course.isBlank() ? null : course.strip();
        Limit max = Limit.of(limits.getGalleryMax());
        if (viewer != null) {
            List<UiBuilderPageJpaRepository.PublishedRow> rows = filter == null
                    ? pages.findByStatusAndDeletedAtIsNullOrderByPublishedAtDescIdDesc(UiBuilderPageEntity.PUBLISHED, max)
                    : pages.findByStatusAndCourseAndDeletedAtIsNullOrderByPublishedAtDescIdDesc(
                            UiBuilderPageEntity.PUBLISHED, filter, max);
            return Result.ok(rows.stream()
                    .map(row -> galleryItem(row.getSlug(), row.getCourse(), row.getPublishedAt(),
                            liveApproval(row.getVisibility(), row.getPublicVersion(), row.getPublicUntil())
                                    ? UiBuilderPageEntity.PUBLIC : UiBuilderPageEntity.SIGNED_IN,
                            row.getPublishedCard() != null ? cardFields(row.getPublishedCard())
                                    : rules.pageFieldsOf(pages.findPublishedSpecById(row.getId()))))
                    .toList());
        }
        return Result.ok(pages.findPublicGallery(filter, clock.millis(), max).stream()
                .map(row -> galleryItem(row.getSlug(), row.getCourse(), row.getPublishedAt(), UiBuilderPageEntity.PUBLIC,
                        row.getPublicCard() != null ? cardFields(row.getPublicCard())
                                : rules.pageFieldsOf(pages.findPublicSpecById(row.getId()))))
                .toList());
    }

    private static GalleryItem galleryItem(String slug, String course, Long publishedAt, String visibility,
            PageFields fields) {
        return new GalleryItem(slug, fields.title(), fields.description(), fields.byline(), course, fields.image(),
                publishedAt, visibility);
    }

    /**
     * One published page. Signed-in viewers get the published copy with the owner's and groups'
     * names; anonymous viewers only a page approved for everyone and not expired, as approved, with
     * group names but no person's name. Anything else is NOT_FOUND. visibility is PUBLIC only while
     * the page is approved for everyone, so a signed-in viewer of an expired approval sees SIGNED_IN.
     */
    @Transactional(readOnly = true)
    public Result<PublishedView> published(Caller viewer, String slug) {
        Optional<UiBuilderPageEntity> found = findPublished(slug);
        if (found.isEmpty()) {
            return Result.refused(Outcome.NOT_FOUND);
        }
        UiBuilderPageEntity page = found.get();
        JsonNode spec;
        Long version;
        if (viewer == null) {
            if (!approvedForEveryone(page)) {
                return Result.refused(Outcome.NOT_FOUND);
            }
            spec = read(page.getPublicSpec());
            version = page.getPublicVersion();
        } else {
            spec = read(page.getPublishedSpec());
            version = page.getPublishedVersion();
        }

        Set<Long> groupIds = new LinkedHashSet<>(groupIdsIn(spec));
        if (page.getGroupId() != null) {
            groupIds.add(page.getGroupId());
        }
        Map<Long, String> groupNames = groupNames(groupIds);
        Set<Long> viewerGroups = viewer == null ? Set.of() : groups.findGroupsByPersonId(viewer.personId()).stream()
                .map(Groups::getId)
                .collect(Collectors.toSet());
        Map<String, GroupView> blockGroups = new LinkedHashMap<>();
        for (Long groupId : groupIdsIn(spec)) {
            String name = groupNames.get(groupId);
            if (name != null) {
                blockGroups.put(String.valueOf(groupId), new GroupView(name, viewerGroups.contains(groupId)));
            }
        }
        String owner = viewer == null ? null : personNames(List.of(page.getOwnerPersonId())).get(page.getOwnerPersonId());
        String group = page.getGroupId() == null ? null : groupNames.get(page.getGroupId());
        boolean canEdit = viewer != null && canEdit(page, viewer);
        PageFields fields = rules.pageFieldsOf(spec);
        return Result.ok(new PublishedView(page.getSlug(), fields.title(), fields.description(), fields.byline(),
                page.getCourse(), fields.image(), page.getPublishedAt(), version,
                approvedForEveryone(page) ? UiBuilderPageEntity.PUBLIC : UiBuilderPageEntity.SIGNED_IN, spec, canEdit,
                canEdit ? page.getId() : null, new MadeBy(owner, group), blockGroups,
                viewer == null ? new Viewer(false, null) : new Viewer(true, viewer.name())));
    }

    /** Opens (or reuses) a direct message from the caller to a published page's owner. */
    public Result<ConversationRef> messageOwner(Caller caller, String slug) {
        Optional<UiBuilderPageEntity> page = findPublished(slug);
        if (page.isEmpty()) {
            return Result.refused(Outcome.NOT_FOUND);
        }
        if (isOwner(page.get(), caller)) {
            return Result.refused(Outcome.OWN_PAGE);
        }
        Optional<Person> owner = people.findById(page.get().getOwnerPersonId());
        Optional<Person> me = people.findById(caller.personId());
        if (owner.isEmpty() || me.isEmpty()) {
            return Result.refused(Outcome.NOT_FOUND);
        }
        DirectMessageConversation conversation = directMessages.getOrCreateConversation(me.get(), List.of(owner.get()));
        return Result.ok(new ConversationRef(conversation.getId()));
    }

    /** The names in a published members block's group: names only, and none for a class group or course channel. */
    @Transactional(readOnly = true)
    public Result<List<MemberName>> members(Caller caller, String slug, String blockId) {
        Optional<UiBuilderPageEntity> page = findPublished(slug);
        if (page.isEmpty()) {
            return Result.refused(Outcome.NOT_FOUND);
        }
        Optional<JsonNode> settings = rules.membersBlockSettings(read(page.get().getPublishedSpec()), blockId);
        if (settings.isEmpty()) {
            return Result.refused(Outcome.NOT_FOUND);
        }
        JsonNode groupId = settings.get().path("group").path("id");
        if (!groupId.isIntegralNumber()) {
            return Result.ok(List.of());
        }
        String name = groupNames(List.of(groupId.asLong())).get(groupId.asLong());
        if (name == null || isRestrictedGroup(name)) {
            return Result.ok(List.of());
        }
        return Result.ok(groups.findGroupMembersRaw(groupId.asLong()).stream()
                .map(row -> row.length > 2 && row[2] != null ? row[2].toString() : null)
                .filter(Objects::nonNull)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .map(MemberName::new)
                .toList());
    }

    private Optional<UiBuilderPageEntity> findPublished(String slug) {
        if (slug == null || "invalid".equals(rules.slugIssue(slug))) {
            return Optional.empty();
        }
        return pages.findBySlugAndStatusAndDeletedAtIsNull(slug, UiBuilderPageEntity.PUBLISHED);
    }

    private boolean approvedForEveryone(UiBuilderPageEntity page) {
        return page.getPublicSpec() != null && liveApproval(page.getVisibility(), page.getPublicVersion(), page.getPublicUntil());
    }

    /** A teacher's approval visitors get now: PUBLIC with an approved version (set with public_spec), not run out. */
    private boolean liveApproval(String visibility, Long publicVersion, Long publicUntil) {
        return UiBuilderPageEntity.PUBLIC.equals(visibility) && publicVersion != null
                && (publicUntil == null || publicUntil > clock.millis());
    }

    // ---- access ----

    /** Takes the row lock, then reads the page; empty when it is missing, deleted or not the caller's to edit. */
    private Optional<UiBuilderPageEntity> lockedPage(long id, Caller caller) {
        if (pages.lockForWrite(id) == 0) {
            return Optional.empty();
        }
        return pages.findById(id).filter(page -> canEdit(page, caller));
    }

    private boolean canEdit(UiBuilderPageEntity page, Caller caller) {
        return caller.teacher() || isOwner(page, caller)
                || editors.existsByPageIdAndPersonId(page.getId(), caller.personId());
    }

    private static boolean isOwner(UiBuilderPageEntity page, Caller caller) {
        return Objects.equals(page.getOwnerPersonId(), caller.personId());
    }

    /** Class groups (Profile's course groups) and course channels are never a page's group. */
    private boolean isRestrictedGroup(String name) {
        String upper = name.strip().toUpperCase(Locale.ROOT);
        if (courseGroups.getGroupNames().contains(upper)) {
            return true;
        }
        String lower = upper.toLowerCase(Locale.ROOT);
        return lower.equals("lessons") || lower.endsWith("-announcements") || lower.contains("-week-");
    }

    private GroupChange groupChange(JsonNode groupId) {
        if (groupId == null) {
            return new GroupChange(false, null, false);
        }
        if (groupId.isNull()) {
            return new GroupChange(true, null, false);
        }
        if (groupId.isIntegralNumber() && groupId.canConvertToLong() && groupId.asLong() > 0) {
            return new GroupChange(true, groupId.asLong(), false);
        }
        return new GroupChange(true, null, true);
    }

    /**
     * The members of a group the caller may make a page's group, or empty when it may not be one:
     * missing, a class group or course channel, or (unless the caller is a teacher) not the caller's.
     */
    private Optional<List<Long>> pageGroupMembers(Caller caller, long groupId) {
        String name = groupNames(List.of(groupId)).get(groupId);
        if (name == null || isRestrictedGroup(name)) {
            return Optional.empty();
        }
        List<Long> members = memberIds(groupId);
        if (!caller.teacher() && !members.contains(caller.personId())) {
            return Optional.empty();
        }
        return Optional.of(members);
    }

    /**
     * Checks the groups a spec's chat and members blocks point at and, when all are allowed, writes
     * Spring's name for each into the spec. A group must exist and not be a class group or course
     * channel; the caller must be a member unless a teacher, or unless the group was already on a
     * stored copy (known), so one teammate's block doesn't stop the others from saving.
     */
    private List<Issue> checkGroupRefs(Caller caller, ObjectNode spec, Set<Long> known) {
        List<GroupRef> refs = rules.groupRefs(spec);
        if (refs.isEmpty()) {
            return List.of();
        }
        Set<Long> ids = refs.stream().map(GroupRef::groupId).collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, String> names = groupNames(ids);
        Map<Long, Boolean> allowed = new HashMap<>();
        for (Long id : ids) {
            String name = names.get(id);
            allowed.put(id, name != null && !isRestrictedGroup(name)
                    && (caller.teacher() || known.contains(id) || memberIds(id).contains(caller.personId())));
        }
        List<Issue> issues = refs.stream()
                .filter(ref -> !allowed.get(ref.groupId()))
                .map(ref -> new Issue(ref.nodeId(), "group", "group_not_allowed"))
                .toList();
        if (issues.isEmpty()) {
            rules.rewriteGroupNames(spec, names);
        }
        return issues;
    }

    private Set<Long> groupIdsIn(JsonNode spec) {
        return rules.groupRefs(spec).stream().map(GroupRef::groupId).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private List<Long> memberIds(long groupId) {
        return groups.findGroupMembersRaw(groupId).stream()
                .map(row -> ((Number) row[0]).longValue())
                .toList();
    }

    /**
     * Makes these people the page's editors (the owner needs no row), deleting and inserting only
     * the rows that differ. Not delete-then-insert: on MySQL a DELETE ... WHERE page_id that finds
     * no rows takes a gap lock, and two pages next to each other in the index (two new team pages,
     * say) would then deadlock on their INSERTs. Deleting by id locks only those rows.
     */
    private void copyEditors(UiBuilderPageEntity page, List<Long> personIds) {
        Set<Long> wanted = personIds.stream()
                .filter(personId -> !personId.equals(page.getOwnerPersonId()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<UiBuilderPageEditorEntity> current = editors.findByPageId(page.getId());
        Set<Long> present = current.stream().map(UiBuilderPageEditorEntity::getPersonId).collect(Collectors.toSet());
        List<Long> removed = current.stream()
                .filter(row -> !wanted.contains(row.getPersonId()))
                .map(UiBuilderPageEditorEntity::getId)
                .toList();
        List<UiBuilderPageEditorEntity> added = wanted.stream()
                .filter(personId -> !present.contains(personId))
                .map(personId -> new UiBuilderPageEditorEntity(null, page.getId(), personId))
                .toList();
        if (!removed.isEmpty()) {
            editors.deleteAllByIdInBatch(removed);
        }
        if (!added.isEmpty()) {
            editors.saveAll(added);
        }
    }

    // ---- revisions kept ----

    /**
     * A save goes into the newest revision when that is a SAVE by the same person started less than
     * ten minutes ago; otherwise it starts a new one. So a long session keeps one copy per ten
     * minutes, and another person's save (or a publish) always starts a new revision. newRevision
     * always starts one: the editor's "Keep mine" replaces a copy that may be the same person's,
     * saved from another tab or device into the newest revision, and that copy must stay there.
     */
    private void recordSave(UiBuilderPageEntity page, Caller caller, long now, boolean newRevision) {
        Optional<UiBuilderPageRevisionEntity> newest = newRevision ? Optional.empty()
                : revisions.findFirstByPageIdOrderByIdDesc(page.getId());
        boolean sameRun = newest.isPresent()
                && UiBuilderPageRevisionEntity.SAVE.equals(newest.get().getKind())
                && Objects.equals(newest.get().getSavedByPersonId(), caller.personId())
                && now - newest.get().getSavedAt() < SAVE_GROUP_MILLIS;
        if (sameRun) {
            UiBuilderPageRevisionEntity revision = newest.get();
            revision.setSpec(page.getDraftSpec());
            revision.setPageVersion(page.getDraftVersion());
            revision.setSavedByName(caller.name());
            revisions.save(revision);
        } else {
            addRevision(page, UiBuilderPageRevisionEntity.SAVE, page.getDraftSpec(), caller, now);
        }
        prune(page.getId());
    }

    private void addRevision(UiBuilderPageEntity page, String kind, String spec, Caller caller, long now) {
        UiBuilderPageRevisionEntity revision = new UiBuilderPageRevisionEntity();
        revision.setPageId(page.getId());
        revision.setPageVersion(page.getDraftVersion());
        revision.setKind(kind);
        revision.setSpec(spec);
        revision.setSavedByPersonId(caller.personId());
        revision.setSavedByName(caller.name());
        revision.setSavedAt(now);
        revisions.save(revision);
    }

    /** Keeps the newest KEEP_SAVES SAVE revisions and every PUBLISH and RESTORE, at most KEEP_REVISIONS in all. */
    private void prune(long pageId) {
        List<Long> remove = new ArrayList<>();
        int saves = 0;
        int kept = 0;
        for (UiBuilderPageRevisionJpaRepository.RevisionRow row : revisions.findByPageIdOrderByIdDesc(pageId)) {
            if (UiBuilderPageRevisionEntity.SAVE.equals(row.getKind()) && ++saves > KEEP_SAVES) {
                remove.add(row.getId());
            } else if (++kept > KEEP_REVISIONS) {
                remove.add(row.getId());
            }
        }
        if (!remove.isEmpty()) {
            revisions.deleteAllByIdInBatch(remove);
        }
    }

    // ---- page fields ----

    private static PageFields requestFields(String title, String description, String byline, String course, String image) {
        return new PageFields(title, description, byline, course, image);
    }

    private static PageFields columnsOf(UiBuilderPageEntity page) {
        return new PageFields(page.getTitle(), page.getDescription(), page.getByline(), page.getCourse(), page.getImage());
    }

    /** Each field from the request, else the spec's page settings, else what the page had. */
    private static PageFields effectiveFields(PageFields request, PageFields spec, PageFields current) {
        PageFields before = current == null ? new PageFields(null, null, null, null, null) : current;
        return new PageFields(
                strip(first(request.title(), spec.title(), before.title())),
                strip(first(request.description(), spec.description(), before.description())),
                strip(first(request.byline(), spec.byline(), before.byline())),
                strip(first(request.course(), spec.course(), before.course(), "")),
                strip(first(request.image(), spec.image(), before.image())));
    }

    @SafeVarargs
    private static <T> T first(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String strip(String value) {
        return value == null ? null : value.strip();
    }

    private static void applyFields(UiBuilderPageEntity page, PageFields fields) {
        page.setTitle(fields.title());
        page.setDescription(fields.description());
        page.setByline(fields.byline());
        page.setCourse(fields.course() == null ? "" : fields.course());
        page.setImage(fields.image());
    }

    /**
     * Writes the page settings Spring keeps in columns into the spec's page object too, so every
     * stored copy (published, approved) carries its own title and gallery picture.
     */
    private static void writePageFields(ObjectNode spec, PageFields fields) {
        if (!(spec.get("page") instanceof ObjectNode page)) {
            return;
        }
        page.put("title", fields.title());
        page.put("course", fields.course() == null ? "" : fields.course());
        if (fields.description() != null) {
            page.put("description", fields.description());
        }
        if (fields.byline() != null) {
            page.put("byline", fields.byline());
        }
        if (fields.image() != null) {
            page.put("image", fields.image());
        }
    }

    /** A stored copy's gallery card: its title, description, byline and picture as a small JSON object. */
    private String card(PageFields fields) {
        ObjectNode card = objectMapper.createObjectNode();
        card.put("title", fields.title());
        card.put("description", fields.description());
        card.put("byline", fields.byline());
        card.put("image", fields.image());
        return write(card);
    }

    private PageFields cardFields(String card) {
        JsonNode node = read(card);
        return new PageFields(UiBuilderSpecRules.textOf(node.get("title")), UiBuilderSpecRules.textOf(node.get("description")),
                UiBuilderSpecRules.textOf(node.get("byline")), null, UiBuilderSpecRules.textOf(node.get("image")));
    }

    private Set<String> courseCodes() {
        return courseGroups.getGroupNames().stream()
                .map(name -> name.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
    }

    // ---- responses ----

    private Result<PageDetail> conflict(UiBuilderPageEntity page) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("currentVersion", page.getDraftVersion());
        details.put("savedByName", page.getUpdatedByName());
        details.put("savedAt", page.getUpdatedAt());
        return refuse(Outcome.VERSION_CONFLICT, details);
    }

    private PageDetail detail(UiBuilderPageEntity page, Caller caller) {
        PageRow row = PROJECTIONS.createProjection(PageRow.class, page);
        Map<Long, String> groupNames = page.getGroupId() == null ? Map.of() : groupNames(List.of(page.getGroupId()));
        Map<Long, String> ownerNames = personNames(List.of(page.getOwnerPersonId()));
        return new PageDetail(summary(row, caller, groupNames, ownerNames), read(page.getDraftSpec()));
    }

    private List<PageSummary> summaries(List<PageRow> rows, Caller caller) {
        Set<Long> groupIds = rows.stream().map(PageRow::getGroupId).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> ownerIds = rows.stream().map(PageRow::getOwnerPersonId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> groupNames = groupNames(groupIds);
        Map<Long, String> ownerNames = personNames(ownerIds);
        return rows.stream().map(row -> summary(row, caller, groupNames, ownerNames)).toList();
    }

    private PageSummary summary(PageRow row, Caller caller, Map<Long, String> groupNames, Map<Long, String> ownerNames) {
        long now = clock.millis();
        boolean leased = row.getLeasePersonId() != null && row.getLeaseExpiresAt() != null && row.getLeaseExpiresAt() > now;
        LeaseView lease = leased
                ? new LeaseView(row.getLeaseName(), row.getLeaseExpiresAt(), Objects.equals(row.getLeasePersonId(), caller.personId()))
                : null;
        boolean published = UiBuilderPageEntity.PUBLISHED.equals(row.getStatus());
        boolean isPublic = UiBuilderPageEntity.PUBLIC.equals(row.getVisibility());
        boolean publicPending = published && isPublic && !Objects.equals(row.getPublicVersion(), row.getPublishedVersion());
        boolean publicExpired = isPublic && row.getPublicUntil() != null && row.getPublicUntil() <= now;
        String groupName = row.getGroupId() == null ? null : groupNames.get(row.getGroupId());
        return new PageSummary(row.getId(), row.getSlug(), row.getTitle(), row.getDescription(), row.getByline(),
                row.getCourse(), row.getImage(), row.getStatus(), row.getVisibility(),
                Boolean.TRUE.equals(row.getPublicRequested()), row.getPublicUntil(), publicPending, publicExpired,
                row.getPublicVersion(), row.getGroupId(), groupName, ownerNames.get(row.getOwnerPersonId()),
                Objects.equals(row.getOwnerPersonId(), caller.personId()),
                row.getDraftVersion() == null ? 0 : row.getDraftVersion(), row.getUpdatedAt(), row.getUpdatedByName(),
                row.getPublishedAt(), row.getPublishedVersion(),
                published && !Objects.equals(row.getDraftVersion(), row.getPublishedVersion()), lease);
    }

    private Map<Long, String> groupNames(Collection<Long> ids) {
        return ids.isEmpty() ? Map.of() : namesOf(pages.findGroupNames(ids));
    }

    private Map<Long, String> personNames(Collection<Long> ids) {
        return ids.isEmpty() ? Map.of() : namesOf(pages.findPersonNames(ids));
    }

    private static Map<Long, String> namesOf(List<NameRow> rows) {
        Map<Long, String> names = new HashMap<>();
        for (NameRow row : rows) {
            if (row.getId() != null && row.getName() != null) {
                names.put(row.getId(), row.getName());
            }
        }
        return names;
    }

    private static void clearPublic(UiBuilderPageEntity page) {
        page.setVisibility(UiBuilderPageEntity.SIGNED_IN);
        page.setPublicSpec(null);
        page.setPublicCard(null);
        page.setPublicVersion(null);
        page.setPublicUntil(null);
        page.setPublicRequested(false);
    }

    private static void clearLease(UiBuilderPageEntity page) {
        page.setLeasePersonId(null);
        page.setLeaseName(null);
        page.setLeaseExpiresAt(null);
    }

    // ---- JSON ----

    private static ObjectNode objectOrNull(JsonNode node) {
        return node instanceof ObjectNode object ? object : null;
    }

    private JsonNode read(String json) {
        if (json == null || json.isEmpty()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            logger.warn("Unreadable UI Builder spec: {}", e.getOriginalMessage());
            return objectMapper.createObjectNode();
        }
    }

    private String write(JsonNode spec) {
        try {
            return objectMapper.writeValueAsString(spec);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not store a UI Builder spec", e);
        }
    }

    /** A refused write keeps nothing it did (for create, the inserted row). */
    private static <T> Result<T> refuse(Outcome outcome) {
        return refuse(outcome, Map.of());
    }

    private static <T> Result<T> refuse(Outcome outcome, Map<String, Object> details) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        }
        return Result.refused(outcome, details);
    }
}
