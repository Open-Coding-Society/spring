package com.open.spring.mvc.assignments;

import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.assignment;
import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.student;
import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.submission;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import org.hibernate.proxy.HibernateProxy;
import org.hibernate.proxy.LazyInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.open.spring.mvc.S3uploads.FileHandler;
import com.open.spring.mvc.aiconnections.AiGradingRouter;
import com.open.spring.mvc.groups.Groups;
import com.open.spring.mvc.groups.Submitter;
import com.open.spring.mvc.person.Person;

/**
 * AI grading reads a handed-in file only from the submitter's own storage folder (a person's uid,
 * or a group's id), which is where the upload routes put it. A submission's content names the
 * folder and the file itself, so a key that points anywhere else is never read.
 */
class AssignmentAiGradingOwnFileTest {

    private static final String WORK = "public class Answer { int answer() { return 42; } }";
    private static final String STORED =
            "assignment-submissions/hw-1/1700000000000_0f8fad5b-d9cb-469f-a165-70867728950e_answer.txt";

    private final AiGradingRouter router = mock(AiGradingRouter.class);
    private final FileHandler files = mock(FileHandler.class);
    private final Person author = student(1L, "student");
    private AssignmentAiGradingService grader;
    private Assignment assignment;

    @BeforeEach
    void setUp() throws Exception {
        grader = new AssignmentAiGradingService(new ObjectMapper(), files, router, "https://github-api.test", "", "");
        assignment = assignment(1L, "hw-1");
        assignment.setHackWeights(AssignmentRubricService.hackWeightsJson(
            "Hack #1 (Weight: 60%) First task. Hack #2 (Weight: 40%) Second task."));
        when(router.generate(anyString(), any(), anyInt())).thenReturn("""
            {"score":0.9,"quality":3,"feedback":"Some work done.",
             "hacks":[{"hack":"Hack #1","score":0.5},{"hack":"Hack #2","score":0.5}]}
            """);
    }

    @Test
    void readsAFileFromTheSubmittersOwnFolder() throws Exception {
        when(files.decodeFile("student", STORED)).thenReturn(encode(WORK));

        assertEquals("graded", grader.grade(fileSubmission(author, "student", STORED)).status());
        verify(router).generate(contains(WORK), same(author), eq(2));
    }

    /** A name the upload routes made keeps its backslash on the server, and S3 treats one as an ordinary character. */
    @Test
    void readsAHandedInNameThatHasABackslash() throws Exception {
        String stored = "assignment-submissions/hw-1/1700000000000_0f8fad5b-d9cb-469f-a165-70867728950e_my\\answer.txt";
        when(files.decodeFile("student", stored)).thenReturn(encode(WORK));

        assertEquals("graded", grader.grade(fileSubmission(author, "student", stored)).status());
        verify(files).decodeFile("student", stored);
    }

    /** Hibernate can hand the submitter back as a proxy; the folder is still the person's uid. */
    @Test
    void readsTheOwnFolderWhenTheSubmitterIsAHibernateProxy() throws Exception {
        Submitter proxy = mock(Submitter.class, withSettings().extraInterfaces(HibernateProxy.class));
        LazyInitializer initializer = mock(LazyInitializer.class);
        when(((HibernateProxy) proxy).asHibernateProxy()).thenReturn((HibernateProxy) proxy);
        when(((HibernateProxy) proxy).getHibernateLazyInitializer()).thenReturn(initializer);
        when(initializer.getImplementation()).thenReturn(author);
        when(files.decodeFile("student", STORED)).thenReturn(encode(WORK));

        assertEquals("graded", grader.grade(fileSubmission(proxy, "student", STORED)).status());
        verify(files).decodeFile("student", STORED);
    }

    @Test
    void neverReadsAFileFromSomeoneElsesFolder() throws Exception {
        assertEquals("not_gradeable", grader.grade(fileSubmission(author, "classmate", STORED)).status());
        verifyNoInteractions(files);
        verify(router, never()).generate(anyString(), any(), anyInt());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "../classmate/" + STORED,
        "assignment-submissions/../../classmate/" + STORED,
        "/classmate/" + STORED,
        "assignment-submissions/./hw-1/answer.txt",
        "assignment-submissions//hw-1/answer.txt",
        "assignment-submissions/hw-1/",
    })
    void neverReadsANameThatLeavesTheFolder(String storedFilename) throws Exception {
        assertEquals("not_gradeable", grader.grade(fileSubmission(author, "student", storedFilename)).status());
        verifyNoInteractions(files);
    }

    /** A group's handed-in files live under the group's id, so a member's own folder is never read for it. */
    @Test
    void aGroupSubmissionNeverReadsAMembersOwnFolder() throws Exception {
        Groups team = new Groups();
        ReflectionTestUtils.setField(team, "id", 42L);
        team.getGroupMembers().add(author);

        assertEquals("not_gradeable", grader.grade(fileSubmission(team, "student", STORED)).status());
        verify(files, never()).decodeFile(eq("student"), anyString());
    }

    private AssignmentSubmission fileSubmission(Submitter submitter, String uploadedBy, String storedFilename) {
        AssignmentSubmission submission = submission(7L, assignment);
        submission.setSubmitter(submitter);
        submission.setContent(new HashMap<>(Map.of("type", "file", "filename", "answer.txt",
                "uploadedBy", uploadedBy, "storedFilename", storedFilename)));
        return submission;
    }

    private static String encode(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }
}
