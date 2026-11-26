package com.open.spring.mvc.S3uploads;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import com.open.spring.mvc.S3uploads.UserFileAccess.Answer;
import com.open.spring.mvc.assignments.Assignment;
import com.open.spring.mvc.assignments.AssignmentAuthorizationService;
import com.open.spring.mvc.assignments.AssignmentSubmission;
import com.open.spring.mvc.assignments.AssignmentSubmissionJPA;
import com.open.spring.mvc.groups.Groups;
import com.open.spring.mvc.groups.GroupsJpaRepository;
import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;

/**
 * Who may use /api/files. Several features share one S3 namespace, `<folder>/<file>`: a
 * person's folder is their uid, a group submission's folder is the group's id, and group chat
 * keeps its messages and shared files under the group's name. So the folder named in a request
 * says nothing about who is asking.
 */
class UserFileAccessTest {

    /** The name both submission upload routes give a handed-in file (buildS3Filename): slug, time, UUID, name. */
    private static final String FILE =
            "assignment-submissions/frq-1/1764000000000_6a1f0b2c-3d4e-4f50-8a6b-7c8d9e0f1a2b_answer.pdf";

    private final PersonJpaRepository people = mock(PersonJpaRepository.class);
    private final AssignmentSubmissionJPA submissions = mock(AssignmentSubmissionJPA.class);
    private final GroupsJpaRepository groups = mock(GroupsJpaRepository.class);
    private UserFileAccess access;

    private Person hop;
    private Person niko;
    private Groups team;

    @BeforeEach
    void setUp() {
        hop = person(1L, "hop", "ROLE_USER", "ROLE_STUDENT");
        niko = person(2L, "niko", "ROLE_USER", "ROLE_STUDENT");
        person(3L, "lena", "ROLE_USER", "ROLE_STUDENT");
        Person creator = person(4L, "cora", "ROLE_USER", "ROLE_STUDENT");
        Person grader = person(5L, "gus", "ROLE_USER", "ROLE_STUDENT");
        Person peerGrader = person(6L, "pia", "ROLE_USER", "ROLE_STUDENT");
        person(7L, "tess", "ROLE_USER", "ROLE_TEACHER");
        person(8L, "root", "ROLE_USER", "ROLE_ADMIN");

        Assignment frq = new Assignment("FRQ 1", "auto-created", "[CONTENT_URL: csa/frq-1/]", 1.0, "10/25/2026", "file");
        frq.setId(50L);
        frq.getCreators().add(creator);
        frq.setAssignedGraders(new ArrayList<>(List.of(grader)));

        AssignmentSubmission hopsWork = submission(70L, frq, hop, "hop/" + FILE);
        hopsWork.setAssignedGraders(new ArrayList<>(List.of(peerGrader)));
        when(submissions.findBySubmitter(hop)).thenReturn(List.of(hopsWork));

        team = new Groups("Sprinters", "3", "csa", new ArrayList<>(List.of(hop, niko)));
        ReflectionTestUtils.setField(team, "id", 900L);
        AssignmentSubmission teamWork = submission(71L, frq, team, "900/" + FILE);
        when(submissions.findBySubmitter(team)).thenReturn(List.of(teamWork));
        when(groups.findByName("Sprinters")).thenReturn(Optional.of(team));
        when(groups.findById(900L)).thenReturn(Optional.of(team));
        when(groups.existsById(900L)).thenReturn(true);

        access = new UserFileAccess(people, submissions, groups, new AssignmentAuthorizationService());
    }

    @Test
    void theSubmitterCanDownloadTheirFile() {
        assertEquals(Answer.ALLOWED, access.download("hop", "hop", FILE));
    }

    /** The Submissions page links graders to /api/files/download, so they keep their downloads. */
    @ParameterizedTest
    @ValueSource(strings = {"tess", "root", "cora", "gus", "pia"})
    void teachersAdminsAndTheAssignmentsGradersCanDownloadIt(String uid) {
        assertEquals(Answer.ALLOWED, access.download(uid, "hop", FILE));
    }

    @Test
    void anotherStudentCannot() {
        assertEquals(Answer.FORBIDDEN, access.download("niko", "hop", FILE));
        assertEquals(Answer.FORBIDDEN, access.download(null, "hop", FILE));
    }

    @Test
    void aGroupsMembersCanDownloadItsSubmission() {
        assertEquals(Answer.ALLOWED, access.download("niko", "900", FILE));
        assertEquals(Answer.FORBIDDEN, access.download("lena", "900", FILE));
    }

    @Test
    void aFileNoSubmissionPointsAtIsNotFound() {
        assertEquals(Answer.NOT_FOUND, access.download("hop", "hop", "assignment-submissions/frq-1/other.pdf"));
        assertEquals(Answer.NOT_FOUND, access.download("root", "nobody", FILE));
    }

    /** A submission's content is the submitter's, so only submissions in the named folder count. */
    @Test
    void aSubmissionCannotPointIntoSomeoneElsesFolder() {
        AssignmentSubmission nikosClaim = submission(72L, null, niko, "hop/" + FILE);
        when(submissions.findBySubmitter(niko)).thenReturn(List.of(nikosClaim));

        assertEquals(Answer.FORBIDDEN, access.download("niko", "hop", FILE));
    }

    /** Group chat storage is reached only through the group chat routes, even by an admin. */
    @ParameterizedTest
    @ValueSource(strings = {"messages-images/messages.jsonl", "shared-files/notes.pdf", "shared-files-meta.json",
            "chat_history.jsonl", "assignment-resources/frq-1/rubric.pdf"})
    void filesOutsideSubmissionsAreNeverServed(String filename) {
        person(9L, "Sprinters", "ROLE_USER", "ROLE_STUDENT"); // an account whose uid is the group's name

        assertEquals(Answer.NOT_FOUND, access.download("root", "Sprinters", filename));
        assertEquals(Answer.NOT_FOUND, access.download("Sprinters", "Sprinters", filename));
        assertEquals(Answer.NOT_FOUND, access.download("root", "900", filename));
        assertEquals(Answer.NOT_FOUND, access.download("hop", "hop", filename));
    }

    /** Submission ids and group ids share one sequence, so a numeric folder is read only as a group's. */
    @Test
    void aNumericFolderBelongsToTheGroupWithThatIdOnly() {
        Person pid = person(901L, "pid", "ROLE_USER", "ROLE_STUDENT");
        AssignmentSubmission claim = submission(75L, null, pid, "901/" + FILE);
        when(submissions.findBySubmitter(pid)).thenReturn(List.of(claim));
        when(submissions.findBySubmitterId(901L)).thenReturn(List.of(claim));

        assertEquals(Answer.NOT_FOUND, access.download("pid", "901", FILE));
    }

    @Test
    void aFolderThatIsBothAUidAndAGroupIdIsRefused() {
        Person sameAsGroup = person(13L, "900", "ROLE_USER", "ROLE_STUDENT");
        AssignmentSubmission claim = submission(76L, null, sameAsGroup, "900/" + FILE);
        when(submissions.findBySubmitter(sameAsGroup)).thenReturn(List.of(claim));

        assertEquals(Answer.NOT_FOUND, access.download("900", "900", FILE));
        assertEquals(Answer.NOT_FOUND, access.download("niko", "900", FILE));
    }

    /**
     * Only the name the server gives a handed-in file counts. Storage under a group's name can
     * sit inside someone's folder (a group name may contain "/"), and it never has that shape.
     */
    @ParameterizedTest
    @ValueSource(strings = {"assignment-submissions/messages-images/messages.jsonl", "assignment-submissions/shared-files-meta.json",
            "assignment-submissions/frq-1/answer.pdf", "assignment-submissions/frq-1/1764_short-id_answer.pdf",
            "assignment-submissions/FRQ-1/1764000000000_6a1f0b2c-3d4e-4f50-8a6b-7c8d9e0f1a2b_answer.pdf",
            "assignment-submissions/frq-1/1764000000000_6a1f0b2c-3d4e-4f50-8a6b-7c8d9e0f1a2b_answer.pdf/x",
            "assignment-submissions/../frq-1/1764000000000_6a1f0b2c-3d4e-4f50-8a6b-7c8d9e0f1a2b_answer.pdf"})
    void onlyTheServersNameForAHandedInFileIsServed(String filename) {
        AssignmentSubmission claim = submission(77L, null, hop, "hop/" + filename);
        when(submissions.findBySubmitter(hop)).thenReturn(List.of(claim));

        assertEquals(Answer.NOT_FOUND, access.download("hop", "hop", filename));
    }

    /** The original name is kept as the student gave it, so a long one or one with a backslash still downloads. */
    @ParameterizedTest
    @ValueSource(strings = {"my\\notes.pdf", "answer with spaces (final).pdf"})
    void anyOriginalNameTheServerKeptStillDownloads(String originalName) {
        String filename = "assignment-submissions/frq-1/1764000000000_6a1f0b2c-3d4e-4f50-8a6b-7c8d9e0f1a2b_" + originalName;
        AssignmentSubmission work = submission(78L, null, hop, "hop/" + filename);
        when(submissions.findBySubmitter(hop)).thenReturn(List.of(work));

        assertEquals(Answer.ALLOWED, access.download("hop", "hop", filename));
    }

    @Test
    void aLongOriginalNameStillDownloads() {
        String filename = "assignment-submissions/frq-1/1764000000000_6a1f0b2c-3d4e-4f50-8a6b-7c8d9e0f1a2b_"
                + "a".repeat(240) + ".pdf";
        AssignmentSubmission work = submission(79L, null, hop, "hop/" + filename);
        when(submissions.findBySubmitter(hop)).thenReturn(List.of(work));

        assertEquals(Answer.ALLOWED, access.download("hop", "hop", filename));
    }

    /** A submitter writes their submission's content, so a path in it isn't proof of a submitted file. */
    @Test
    void aSubmissionPointingAtChatStorageDoesNotUnlockIt() {
        Person sameName = person(9L, "Sprinters", "ROLE_USER", "ROLE_STUDENT");
        AssignmentSubmission claim = submission(73L, null, sameName, "Sprinters/messages-images/messages.jsonl");
        when(submissions.findBySubmitter(sameName)).thenReturn(List.of(claim));

        assertEquals(Answer.NOT_FOUND, access.download("Sprinters", "Sprinters", "messages-images/messages.jsonl"));
    }

    @Test
    void youCanUploadToYourOwnFolder() {
        assertEquals(Answer.ALLOWED, access.upload("hop", "hop", "notes/todo.txt"));
    }

    @Test
    void notToSomeoneElsesFolder() {
        assertEquals(Answer.FORBIDDEN, access.upload("niko", "hop", "notes/todo.txt"));
        assertEquals(Answer.FORBIDDEN, access.upload("root", "hop", "notes/todo.txt"));
        assertEquals(Answer.FORBIDDEN, access.upload(null, "hop", "notes/todo.txt"));
    }

    /** A handed-in file must stay what was handed in, so only the submission routes write those. */
    @Test
    void notOverASubmittedFile() {
        assertEquals(Answer.FORBIDDEN, access.upload("hop", "hop", FILE));
    }

    @Test
    void notIntoAFolderAGroupUses() {
        person(9L, "Sprinters", "ROLE_USER", "ROLE_STUDENT");
        person(10L, "900", "ROLE_USER", "ROLE_STUDENT");

        assertEquals(Answer.FORBIDDEN, access.upload("Sprinters", "Sprinters", "messages-images/messages.jsonl"));
        assertEquals(Answer.FORBIDDEN, access.upload("900", "900", "chat_history.jsonl"));
    }

    /** Deleting a group leaves its chat in S3 under its name, so the name alone can't prove there's no group chat there. */
    @ParameterizedTest
    @ValueSource(strings = {"messages-images/messages.jsonl", "shared-files/notes.pdf", "shared-files-meta.json",
            "chat_history.jsonl"})
    void notOverGroupChatFilesEvenAfterTheGroupIsGone(String filename) {
        person(11L, "Oldteam", "ROLE_USER", "ROLE_STUDENT"); // no group is called Oldteam any more

        assertEquals(Answer.FORBIDDEN, access.upload("Oldteam", "Oldteam", filename));
    }

    /** A group name may contain "/", so a group's chat can sit deeper inside someone's folder. */
    @ParameterizedTest
    @ValueSource(strings = {"g/messages-images/messages.jsonl", "a/b/shared-files/notes.pdf", "g/shared-files-meta.json",
            "g/chat_history.jsonl"})
    void notOverGroupChatFilesAtAnyDepth(String filename) {
        assertEquals(Answer.FORBIDDEN, access.upload("hop", "hop", filename));
    }

    @Test
    void otherNamesInYourFolderAreFine() {
        assertEquals(Answer.ALLOWED, access.upload("hop", "hop", "notes/messages.jsonl"));
        assertEquals(Answer.ALLOWED, access.upload("hop", "hop", "shared-files-notes/todo.txt"));
    }

    /**
     * A storage key is used as written, but a client or proxy on the way to S3 may resolve a
     * dot segment, so a name that could step out of its folder is refused like group chat's own.
     */
    @ParameterizedTest
    @ValueSource(strings = {"../Sprinters/messages-images/messages.jsonl", "notes/../../Sprinters/shared-files/a.pdf",
            "/notes.txt", "notes//todo.txt", "notes/./todo.txt", "notes\\todo.txt", "notes/\t.txt", ""})
    void namesThatCouldLeaveTheFolderAreRefused(String filename) {
        assertEquals(Answer.FORBIDDEN, access.upload("hop", "hop", filename));
    }

    @ParameterizedTest
    @ValueSource(strings = {"hop/notes", "..", ".", "", " "})
    void aFolderIsOneCleanName(String folder) {
        person(12L, folder, "ROLE_USER", "ROLE_STUDENT");

        assertEquals(Answer.FORBIDDEN, access.upload(folder, folder, "notes/todo.txt"));
        assertEquals(Answer.FORBIDDEN, access.deleteFolder("root", folder));
        assertEquals(Answer.NOT_FOUND, access.download("root", folder, FILE));
    }

    @Test
    void aSubmissionPathWithDotSegmentsIsNotServed() {
        String stepsOut = "assignment-submissions/../../Sprinters/messages-images/messages.jsonl";
        AssignmentSubmission claim = submission(74L, null, hop, "hop/" + stepsOut);
        when(submissions.findBySubmitter(hop)).thenReturn(List.of(claim));

        assertEquals(Answer.NOT_FOUND, access.download("hop", "hop", stepsOut));
    }

    /** Deleting a folder removes every file in it, submitted ones too. No page calls it. */
    @Test
    void onlyAnAdminDeletesAFolder() {
        assertEquals(Answer.ALLOWED, access.deleteFolder("root", "hop"));
        assertEquals(Answer.FORBIDDEN, access.deleteFolder("hop", "hop"));
        assertEquals(Answer.FORBIDDEN, access.deleteFolder("tess", "hop"));
        assertEquals(Answer.FORBIDDEN, access.deleteFolder(null, "hop"));
    }

    /** A group name may contain "/", so a group's chat can sit inside a person's folder, and deleting it would remove that too. */
    @Test
    void notAFolderThatHoldsAGroupsStorage() {
        when(groups.existsByNameStartingWith("hop/")).thenReturn(true);

        assertEquals(Answer.FORBIDDEN, access.deleteFolder("root", "hop"));
        assertEquals(Answer.ALLOWED, access.deleteFolder("root", "niko"));
    }

    @Test
    void anEmptyOriginalNameStillDownloads() {
        String filename = "assignment-submissions/frq-1/1764000000000_6a1f0b2c-3d4e-4f50-8a6b-7c8d9e0f1a2b_";
        AssignmentSubmission work = submission(80L, null, hop, "hop/" + filename);
        when(submissions.findBySubmitter(hop)).thenReturn(List.of(work));

        assertEquals(Answer.ALLOWED, access.download("hop", "hop", filename));
    }

    @Test
    void notAFolderAGroupUses() {
        assertEquals(Answer.FORBIDDEN, access.deleteFolder("root", "Sprinters"));
        assertEquals(Answer.FORBIDDEN, access.deleteFolder("root", "900"));
    }

    private Person person(Long id, String uid, String... roles) {
        Person person = new Person();
        ReflectionTestUtils.setField(person, "id", id);
        person.setUid(uid);
        person.setName(uid);
        person.setRoles(new ArrayList<>(List.of(roles)));
        when(people.findByUid(uid)).thenReturn(person);
        return person;
    }

    private static AssignmentSubmission submission(Long id, Assignment assignment,
            com.open.spring.mvc.groups.Submitter submitter, String storagePath) {
        AssignmentSubmission submission = new AssignmentSubmission();
        submission.setId(id);
        submission.setAssignment(assignment);
        submission.setSubmitter(submitter);
        submission.setContent(Map.of("type", "file", "storagePath", storagePath));
        return submission;
    }
}
