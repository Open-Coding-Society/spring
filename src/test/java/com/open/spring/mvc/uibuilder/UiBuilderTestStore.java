package com.open.spring.mvc.uibuilder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Predicate;

import org.springframework.beans.BeanUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.data.projection.ProjectionFactory;
import org.springframework.data.projection.SpelAwareProxyProjectionFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.open.spring.mvc.directmessages.DirectMessageConversation;
import com.open.spring.mvc.directmessages.DirectMessageService;
import com.open.spring.mvc.groups.CourseGroupProperties;
import com.open.spring.mvc.groups.Groups;
import com.open.spring.mvc.groups.GroupsJpaRepository;
import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;
import com.open.spring.mvc.uibuilder.UiBuilderPageJpaRepository.NameRow;
import com.open.spring.mvc.uibuilder.UiBuilderPageJpaRepository.PageRow;
import com.open.spring.mvc.uibuilder.UiBuilderPageJpaRepository.PublicRow;
import com.open.spring.mvc.uibuilder.UiBuilderPageJpaRepository.PublishedRow;
import com.open.spring.mvc.uibuilder.UiBuilderPageRevisionJpaRepository.RevisionRow;
import com.open.spring.mvc.uibuilder.UiBuilderPageService.Caller;

/**
 * The UI Builder tables, and the people and groups they point at, as in-memory maps behind Mockito
 * mocks of the repositories. Rows are copied in and out like a database would, so the service only
 * keeps what it saves. Not a test itself: UiBuilderPageServiceTest and UiBuilderApiHttpTest use it.
 */
class UiBuilderTestStore {
    static final Instant NOW = Instant.parse("2026-10-28T17:00:00Z");

    final MutableClock clock = new MutableClock(NOW);
    final ObjectMapper json = new ObjectMapper();
    final UiBuilderLimits limits = new UiBuilderLimits();
    final UiBuilderSpecRules rules = new UiBuilderSpecRules(limits, json);
    final CourseGroupProperties courses = new CourseGroupProperties();

    final UiBuilderPageJpaRepository pages = mock(UiBuilderPageJpaRepository.class);
    final UiBuilderPageRevisionJpaRepository revisions = mock(UiBuilderPageRevisionJpaRepository.class);
    final UiBuilderPageEditorJpaRepository editors = mock(UiBuilderPageEditorJpaRepository.class);
    final PersonJpaRepository people = mock(PersonJpaRepository.class);
    final GroupsJpaRepository groups = mock(GroupsJpaRepository.class);
    final DirectMessageService directMessages = mock(DirectMessageService.class);
    final UiBuilderPageService service;

    final Map<Long, UiBuilderPageEntity> pageRows = new TreeMap<>();
    final Map<Long, UiBuilderPageRevisionEntity> revisionRows = new TreeMap<>();
    final Map<Long, UiBuilderPageEditorEntity> editorRows = new TreeMap<>();
    final Map<Long, Person> persons = new HashMap<>();
    final Map<Long, Groups> groupRows = new HashMap<>();
    final Map<Long, Set<Long>> groupMembers = new HashMap<>();
    final List<List<Long>> conversations = new ArrayList<>();

    private final ProjectionFactory projections = new SpelAwareProxyProjectionFactory();
    private long nextPageId = 1;
    private long nextRevisionId = 1;
    private long nextEditorId = 1;

    UiBuilderTestStore() {
        courses.setClassGroups(List.of("CSA", "CSP", "CSH", "CSSE"));
        stubPages();
        stubRevisions();
        stubEditors();
        stubPeopleAndGroups();
        service = new UiBuilderPageService(pages, revisions, editors, people, groups, directMessages, courses, rules,
                limits, json, clock);
    }

    // ---- accounts and groups ----

    Person person(long id, String uid, String name) {
        Person person = new Person();
        ReflectionTestUtils.setField(person, "id", id);
        person.setUid(uid);
        person.setName(name);
        person.setEmail(uid + "@example.com");
        persons.put(id, person);
        return person;
    }

    Groups group(long id, String name, Long... memberIds) {
        Groups group = new Groups();
        ReflectionTestUtils.setField(group, "id", id);
        group.setName(name);
        groupRows.put(id, group);
        groupMembers.put(id, new LinkedHashSet<>(List.of(memberIds)));
        return group;
    }

    static Caller student(Person person) {
        return new Caller(person.getId(), person.getUid(), person.getName(), false);
    }

    static Caller teacher(Person person) {
        return new Caller(person.getId(), person.getUid(), person.getName(), true);
    }

    /** The login a JWT cookie would give this person: their uid and roles. */
    static UsernamePasswordAuthenticationToken login(Person person, String... roles) {
        return new UsernamePasswordAuthenticationToken(person.getUid(), null,
                Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList());
    }

    // ---- specs ----

    /** A small valid page: one section with a header block. */
    ObjectNode simplePage(String title) {
        try {
            return (ObjectNode) json.readTree("""
                    {"schemaVersion": 1, "kind": "ocs-ui-page",
                     "page": {"title": "%s", "description": "", "byline": "", "course": "csp", "width": "contained", "image": ""},
                     "sections": [{"id": "s-main0001", "type": "stack", "settings": {}, "hidden": false,
                       "cells": [{"blocks": [{"id": "b-hero0001", "type": "hero", "settings": {"title": "%s"}, "hidden": false}]}]}]}
                    """.formatted(title, title));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A shared fixture from src/test/resources/ui-builder/, such as "all-blocks.json" or "invalid/bad-id.json". */
    ObjectNode fixture(String path) {
        try (InputStream in = UiBuilderTestStore.class.getResourceAsStream("/ui-builder/" + path)) {
            if (in == null) {
                throw new IllegalArgumentException("No fixture " + path);
            }
            return (ObjectNode) json.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    UiBuilderPageEntity stored(long pageId) {
        return pageRows.get(pageId);
    }

    List<UiBuilderPageRevisionEntity> revisionsOf(long pageId) {
        return revisionRows.values().stream()
                .filter(row -> row.getPageId().equals(pageId))
                .sorted(Comparator.comparing(UiBuilderPageRevisionEntity::getId).reversed())
                .toList();
    }

    List<Long> editorsOf(long pageId) {
        return editorRows.values().stream()
                .filter(row -> row.getPageId().equals(pageId))
                .map(UiBuilderPageEditorEntity::getPersonId)
                .sorted()
                .toList();
    }

    // ---- stubs ----

    private void stubPages() {
        when(pages.lockForWrite(anyLong())).thenAnswer(call -> {
            UiBuilderPageEntity row = pageRows.get(call.<Long>getArgument(0));
            return row != null && row.getDeletedAt() == null ? 1 : 0;
        });
        when(pages.findById(anyLong())).thenAnswer(call -> Optional.ofNullable(pageRows.get(call.<Long>getArgument(0)))
                .map(UiBuilderTestStore::copy));
        when(pages.findByIdAndDeletedAtIsNull(anyLong())).thenAnswer(call -> Optional
                .ofNullable(pageRows.get(call.<Long>getArgument(0)))
                .filter(row -> row.getDeletedAt() == null)
                .map(UiBuilderTestStore::copy));
        when(pages.save(any(UiBuilderPageEntity.class))).thenAnswer(call -> {
            UiBuilderPageEntity row = call.getArgument(0);
            if (row.getId() == null) {
                row.setId(nextPageId++);
            }
            boolean slugTaken = row.getSlug() != null && pageRows.values().stream()
                    .anyMatch(other -> !other.getId().equals(row.getId()) && row.getSlug().equals(other.getSlug()));
            if (slugTaken) {
                throw new DataIntegrityViolationException("UNIQUE constraint failed: ui_builder_page.slug");
            }
            pageRows.put(row.getId(), copy(row));
            return row;
        });
        when(pages.existsBySlug(anyString())).thenAnswer(call -> pageRows.values().stream()
                .anyMatch(row -> call.getArgument(0).equals(row.getSlug())));
        when(pages.findBySlugAndStatusAndDeletedAtIsNull(anyString(), anyString())).thenAnswer(call -> pageRows.values()
                .stream()
                .filter(row -> call.getArgument(0).equals(row.getSlug()) && call.getArgument(1).equals(row.getStatus())
                        && row.getDeletedAt() == null)
                .findFirst()
                .map(UiBuilderTestStore::copy));
        when(pages.findByOwnerPersonIdAndDeletedAtIsNull(anyLong())).thenAnswer(call -> pageRows(
                row -> row.getOwnerPersonId().equals(call.getArgument(0)) && row.getDeletedAt() == null, null, PageRow.class));
        when(pages.findByIdInAndDeletedAtIsNull(anyCollection())).thenAnswer(call -> pageRows(
                row -> call.<Collection<Long>>getArgument(0).contains(row.getId()) && row.getDeletedAt() == null, null,
                PageRow.class));
        when(pages.findByCourseAndDeletedAtIsNullOrderByUpdatedAtDescIdDesc(anyString(), any(Limit.class)))
                .thenAnswer(call -> limited(pageRows(row -> call.getArgument(0).equals(row.getCourse())
                        && row.getDeletedAt() == null, BY_UPDATED, PageRow.class), call.getArgument(1)));
        when(pages.findByDeletedAtIsNullOrderByUpdatedAtDescIdDesc(any(Limit.class))).thenAnswer(call -> limited(
                pageRows(row -> row.getDeletedAt() == null, BY_UPDATED, PageRow.class), call.getArgument(0)));
        when(pages.findByStatusAndDeletedAtIsNullOrderByPublishedAtDescIdDesc(anyString(), any(Limit.class)))
                .thenAnswer(call -> limited(pageRows(row -> call.getArgument(0).equals(row.getStatus())
                        && row.getDeletedAt() == null, BY_PUBLISHED, PublishedRow.class), call.getArgument(1)));
        when(pages.findByStatusAndCourseAndDeletedAtIsNullOrderByPublishedAtDescIdDesc(anyString(), anyString(), any(Limit.class)))
                .thenAnswer(call -> limited(pageRows(row -> call.getArgument(0).equals(row.getStatus())
                        && call.getArgument(1).equals(row.getCourse()) && row.getDeletedAt() == null, BY_PUBLISHED,
                        PublishedRow.class), call.getArgument(2)));
        when(pages.findPublicGallery(nullable(String.class), anyLong(), any(Limit.class))).thenAnswer(call -> {
            String course = call.getArgument(0);
            long now = call.getArgument(1);
            return limited(pageRows(row -> UiBuilderPageEntity.PUBLISHED.equals(row.getStatus()) && row.getDeletedAt() == null
                    && UiBuilderPageEntity.PUBLIC.equals(row.getVisibility()) && row.getPublicSpec() != null
                    && (row.getPublicUntil() == null || row.getPublicUntil() > now)
                    && (course == null || course.equals(row.getCourse())), BY_PUBLISHED, PublicRow.class), call.getArgument(2));
        });
        when(pages.approveCourse(anyString(), nullable(Long.class))).thenAnswer(call -> {
            int updated = 0;
            for (UiBuilderPageEntity row : pageRows.values()) {
                if (UiBuilderPageEntity.PUBLISHED.equals(row.getStatus()) && call.getArgument(0).equals(row.getCourse())
                        && row.getDeletedAt() == null) {
                    row.setVisibility(UiBuilderPageEntity.PUBLIC);
                    row.setPublicSpec(row.getPublishedSpec());
                    row.setPublicVersion(row.getPublishedVersion());
                    row.setPublicRequested(false);
                    row.setPublicUntil(call.getArgument(1));
                    updated++;
                }
            }
            return updated;
        });
        when(pages.findPersonNames(anyCollection())).thenAnswer(call -> names(call.<Collection<Long>>getArgument(0),
                id -> persons.containsKey(id) ? persons.get(id).getName() : null));
        when(pages.findGroupNames(anyCollection())).thenAnswer(call -> names(call.<Collection<Long>>getArgument(0),
                id -> groupRows.containsKey(id) ? groupRows.get(id).getName() : null));
    }

    private void stubRevisions() {
        when(revisions.save(any(UiBuilderPageRevisionEntity.class))).thenAnswer(call -> {
            UiBuilderPageRevisionEntity row = call.getArgument(0);
            if (row.getId() == null) {
                row.setId(nextRevisionId++);
            }
            revisionRows.put(row.getId(), copy(row));
            return row;
        });
        when(revisions.findByPageIdOrderByIdDesc(anyLong())).thenAnswer(call -> revisionsOf(call.<Long>getArgument(0))
                .stream()
                .map(row -> projections.createProjection(RevisionRow.class, copy(row)))
                .toList());
        when(revisions.findFirstByPageIdOrderByIdDesc(anyLong())).thenAnswer(call -> revisionsOf(call.<Long>getArgument(0))
                .stream().findFirst().map(UiBuilderTestStore::copy));
        when(revisions.findByIdAndPageId(anyLong(), anyLong())).thenAnswer(call -> Optional
                .ofNullable(revisionRows.get(call.<Long>getArgument(0)))
                .filter(row -> row.getPageId().equals(call.getArgument(1)))
                .map(UiBuilderTestStore::copy));
        when(revisions.deleteByPageId(anyLong())).thenAnswer(call -> {
            List<Long> ids = revisionsOf(call.<Long>getArgument(0)).stream().map(UiBuilderPageRevisionEntity::getId).toList();
            ids.forEach(revisionRows::remove);
            return ids.size();
        });
        doAnswer(call -> {
            call.<Iterable<Long>>getArgument(0).forEach(revisionRows::remove);
            return null;
        }).when(revisions).deleteAllByIdInBatch(anyIterable());
    }

    private void stubEditors() {
        when(editors.existsByPageIdAndPersonId(anyLong(), anyLong())).thenAnswer(call -> editorRows.values().stream()
                .anyMatch(row -> row.getPageId().equals(call.getArgument(0)) && row.getPersonId().equals(call.getArgument(1))));
        when(editors.findByPersonId(anyLong())).thenAnswer(call -> editorRows.values().stream()
                .filter(row -> row.getPersonId().equals(call.getArgument(0)))
                .map(row -> new UiBuilderPageEditorEntity(row.getId(), row.getPageId(), row.getPersonId()))
                .toList());
        when(editors.deleteByPageId(anyLong())).thenAnswer(call -> {
            List<Long> ids = editorRows.values().stream()
                    .filter(row -> row.getPageId().equals(call.getArgument(0)))
                    .map(UiBuilderPageEditorEntity::getId)
                    .toList();
            ids.forEach(editorRows::remove);
            return ids.size();
        });
        when(editors.saveAll(anyList())).thenAnswer(call -> {
            List<UiBuilderPageEditorEntity> rows = call.getArgument(0);
            for (UiBuilderPageEditorEntity row : rows) {
                boolean duplicate = editorRows.values().stream().anyMatch(other -> other.getPageId().equals(row.getPageId())
                        && other.getPersonId().equals(row.getPersonId()));
                if (duplicate) {
                    throw new DataIntegrityViolationException("UNIQUE constraint failed: ui_builder_page_editor");
                }
                row.setId(nextEditorId++);
                editorRows.put(row.getId(), new UiBuilderPageEditorEntity(row.getId(), row.getPageId(), row.getPersonId()));
            }
            return rows;
        });
    }

    private void stubPeopleAndGroups() {
        when(people.findByUid(anyString())).thenAnswer(call -> persons.values().stream()
                .filter(person -> call.getArgument(0).equals(person.getUid()))
                .findFirst()
                .orElse(null));
        when(people.findById(anyLong())).thenAnswer(call -> Optional.ofNullable(persons.get(call.<Long>getArgument(0))));
        when(groups.findGroupMembersRaw(anyLong())).thenAnswer(call -> groupMembers
                .getOrDefault(call.<Long>getArgument(0), Set.of()).stream()
                .filter(persons::containsKey)
                .map(id -> new Object[] {id, persons.get(id).getUid(), persons.get(id).getName(), persons.get(id).getEmail()})
                .toList());
        when(groups.findGroupsByPersonId(anyLong())).thenAnswer(call -> groupMembers.entrySet().stream()
                .filter(entry -> entry.getValue().contains(call.<Long>getArgument(0)))
                .map(entry -> groupRows.get(entry.getKey()))
                .filter(Objects::nonNull)
                .toList());
        when(directMessages.getOrCreateConversation(any(Person.class), anyList())).thenAnswer(call -> {
            Person requester = call.getArgument(0);
            List<Person> others = call.getArgument(1);
            List<Long> participants = new ArrayList<>();
            participants.add(requester.getId());
            others.forEach(other -> participants.add(other.getId()));
            int index = conversations.indexOf(participants);
            if (index < 0) {
                conversations.add(participants);
                index = conversations.size() - 1;
            }
            DirectMessageConversation conversation = new DirectMessageConversation();
            conversation.setId(100L + index);
            return conversation;
        });
    }

    private static final Comparator<UiBuilderPageEntity> BY_UPDATED = Comparator
            .comparing(UiBuilderPageEntity::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(UiBuilderPageEntity::getId, Comparator.reverseOrder());

    private static final Comparator<UiBuilderPageEntity> BY_PUBLISHED = Comparator
            .comparing(UiBuilderPageEntity::getPublishedAt, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(UiBuilderPageEntity::getId, Comparator.reverseOrder());

    private <T> List<T> pageRows(Predicate<UiBuilderPageEntity> filter, Comparator<UiBuilderPageEntity> order, Class<T> type) {
        return pageRows.values().stream()
                .filter(filter)
                .sorted(order == null ? Comparator.comparing(UiBuilderPageEntity::getId) : order)
                .map(row -> projections.createProjection(type, copy(row)))
                .toList();
    }

    private static <T> List<T> limited(List<T> rows, Limit limit) {
        return limit.isLimited() ? rows.stream().limit(limit.max()).toList() : rows;
    }

    private List<NameRow> names(Collection<Long> ids, Function<Long, String> nameOf) {
        return ids.stream()
                .filter(id -> nameOf.apply(id) != null)
                .map(id -> projections.createProjection(NameRow.class, Map.of("id", id, "name", nameOf.apply(id))))
                .toList();
    }

    static UiBuilderPageEntity copy(UiBuilderPageEntity row) {
        UiBuilderPageEntity copy = new UiBuilderPageEntity();
        BeanUtils.copyProperties(row, copy);
        return copy;
    }

    static UiBuilderPageRevisionEntity copy(UiBuilderPageRevisionEntity row) {
        UiBuilderPageRevisionEntity copy = new UiBuilderPageRevisionEntity();
        BeanUtils.copyProperties(row, copy);
        return copy;
    }

    /** A clock the tests move forward. */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
