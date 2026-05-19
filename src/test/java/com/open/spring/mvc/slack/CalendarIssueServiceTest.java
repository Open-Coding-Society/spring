package com.open.spring.mvc.slack;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class CalendarIssueServiceTest {

    @Mock
    private CalendarIssueRepository calendarIssueRepository;

    @Mock
    private com.open.spring.mvc.person.PersonJpaRepository personJpaRepository;

    @Mock
    private com.open.spring.mvc.groups.GroupsJpaRepository groupsJpaRepository;

    @InjectMocks
    private CalendarIssueService issueService;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    public void testMatchesTags_andAuthor_andDateRange() {
        CalendarIssue a = new CalendarIssue();
        a.setTitle("Test");
        a.setTags("bug,urgent");
        a.setOwnerUid("alice");
        a.setDueDate(LocalDate.of(2026, 1, 20));
        // use reflection to set createdAt (no public setter)
        try {
            java.lang.reflect.Field f = CalendarIssue.class.getDeclaredField("createdAt");
            f.setAccessible(true);
            f.set(a, java.time.LocalDateTime.of(2026,1,10,0,0));
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }

        when(calendarIssueRepository.findAll()).thenReturn(List.of(a));

        List<CalendarIssue> res = issueService.getIssues(null, null, null, null, null,
                "alice", "bug", LocalDate.of(2026,1,1), LocalDate.of(2026,1,31), null, "alice", true);

        assertNotNull(res);
        assertEquals(1, res.size());
        assertEquals("Test", res.get(0).getTitle());
    }

    @Test
    public void testLegacyIssueWithoutOwnerOrGroupRemainsVisible() {
        CalendarIssue legacy = new CalendarIssue();
        legacy.setTitle("Legacy issue");
        legacy.setDueDate(LocalDate.of(2026, 2, 14));
        when(calendarIssueRepository.findAll()).thenReturn(List.of(legacy));

        List<CalendarIssue> res = issueService.getIssues(null, null, null, null, null,
                null, null, null, null, null, "alice", false);

        assertEquals(1, res.size());
        assertEquals("Legacy issue", res.get(0).getTitle());
    }
}
