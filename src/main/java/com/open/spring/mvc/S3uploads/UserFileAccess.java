package com.open.spring.mvc.S3uploads;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.open.spring.mvc.assignments.AssignmentAuthorizationService;
import com.open.spring.mvc.assignments.AssignmentSubmission;
import com.open.spring.mvc.assignments.AssignmentSubmissionJPA;
import com.open.spring.mvc.groups.Groups;
import com.open.spring.mvc.groups.GroupsJpaRepository;
import com.open.spring.mvc.groups.SharedFilePaths;
import com.open.spring.mvc.groups.Submitter;
import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;

/**
 * Who may use /api/files. Stored files share one S3 namespace, {@code <folder>/<filename>}: a
 * person's folder is their uid, a group submission's folder is the group's id, and group chat
 * keeps its messages and shared files under the group's name (ChatService under its id). So the
 * folder a request names says nothing about who is asking, and these checks decide instead:
 * <ul>
 * <li>Download serves only handed-in files, to the people who can see that submission.</li>
 * <li>Upload writes only to your own folder, never over a handed-in file or group chat's files,
 * never into a group's folder.</li>
 * <li>Deleting a whole folder is for admins, and never a group's folder.</li>
 * </ul>
 * Group chat storage is reached only through the group chat routes.
 */
@Service
@Transactional(readOnly = true) // open-in-view is off, and the checks read lazy members and graders
public class UserFileAccess {

    public enum Answer { ALLOWED, FORBIDDEN, NOT_FOUND }

    /** Both submission upload routes put a handed-in file under this name (buildS3Filename). */
    static final String SUBMISSION_FILES = "assignment-submissions/";

    /**
     * The whole name buildS3Filename gives a handed-in file: the assignment's slug, the time, a
     * random UUID and the original name. Nothing else stored in a folder has this shape.
     */
    private static final Pattern HANDED_IN_FILE = Pattern.compile(Pattern.quote(SUBMISSION_FILES)
            + "[a-z0-9]+(?:-[a-z0-9]+)*/[0-9]+_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}_[^/]*");

    /**
     * Group chat's own names: GroupChatService's messages and shared files, and ChatService's
     * history. Deleting a group leaves them in S3 under its name, and a group name may contain
     * "/", so they're refused at any depth.
     */
    private static final Set<String> GROUP_CHAT_NAMES = Set.of(
            "messages-images", "shared-files", "shared-files-meta.json", "chat_history.jsonl");

    private final PersonJpaRepository people;
    private final AssignmentSubmissionJPA submissions;
    private final GroupsJpaRepository groups;
    private final AssignmentAuthorizationService authorization;

    public UserFileAccess(PersonJpaRepository people, AssignmentSubmissionJPA submissions, GroupsJpaRepository groups,
            AssignmentAuthorizationService authorization) {
        this.people = people;
        this.submissions = submissions;
        this.groups = groups;
        this.authorization = authorization;
    }

    /**
     * Only a handed-in file whose path one of the folder owner's submissions holds, for its
     * submitters and for whoever may manage it: teachers, admins, the assignment's creators and
     * graders, and graders of that submission.
     */
    public Answer download(String callerUid, String folder, String filename) {
        if (!isCleanFolder(folder) || filename == null || !HANDED_IN_FILE.matcher(filename).matches()) {
            return Answer.NOT_FOUND;
        }
        Submitter owner = folderOwner(folder);
        if (owner == null) {
            return Answer.NOT_FOUND;
        }
        String storagePath = folder + "/" + filename;
        List<AssignmentSubmission> holders = submissions.findBySubmitter(owner).stream()
                .filter(submission -> storagePath.equals(storagePathOf(submission)))
                .toList();
        if (holders.isEmpty()) {
            return Answer.NOT_FOUND;
        }
        Person caller = callerUid == null ? null : people.findByUid(callerUid);
        boolean allowed = holders.stream().anyMatch(submission ->
                authorization.isSubmitter(caller, submission) || authorization.canManageSubmission(caller, submission));
        return allowed ? Answer.ALLOWED : Answer.FORBIDDEN;
    }

    public Answer upload(String callerUid, String folder, String filename) {
        boolean allowed = callerUid != null && callerUid.equals(folder) && isCleanFolder(folder)
                && isCleanName(filename) && !filename.startsWith(SUBMISSION_FILES) && !isGroupChatName(filename)
                && !isGroupFolder(folder);
        return allowed ? Answer.ALLOWED : Answer.FORBIDDEN;
    }

    /**
     * A folder holds a person's handed-in files too, and no page deletes one. Deleting removes
     * everything under {@code <folder>/}, so a group whose name starts with that keeps it too.
     */
    public Answer deleteFolder(String callerUid, String folder) {
        Person caller = callerUid == null ? null : people.findByUid(callerUid);
        boolean allowed = caller != null && caller.hasRole(AssignmentAuthorizationService.ROLE_ADMIN)
                && isCleanFolder(folder) && !isGroupFolder(folder) && !groups.existsByNameStartingWith(folder + "/");
        return allowed ? Answer.ALLOWED : Answer.FORBIDDEN;
    }

    /**
     * Who a folder belongs to: the person with that uid, or the group whose id it is, the way
     * resolveStorageUid writes a group submission's folder. A folder that could be either belongs
     * to neither, and a person's id never makes a folder theirs.
     */
    private Submitter folderOwner(String folder) {
        Person person = people.findByUid(folder);
        Long groupId = groupId(folder);
        Groups group = groupId == null ? null : groups.findById(groupId).orElse(null);
        if (person != null && group != null) {
            return null;
        }
        return person != null ? person : group;
    }

    private boolean isGroupFolder(String folder) {
        if (folder == null || groups.findByName(folder).isPresent()) {
            return true;
        }
        Long groupId = groupId(folder);
        return groupId != null && groups.existsById(groupId);
    }

    /**
     * A key is used as written, but something on the way to S3 may resolve "..", so names follow
     * group chat's shared-file rule, and a folder is one of those names.
     */
    private static boolean isCleanName(String filename) {
        return SharedFilePaths.clean(filename) != null;
    }

    private static boolean isCleanFolder(String folder) {
        return isCleanName(folder) && folder.indexOf('/') < 0;
    }

    private static boolean isGroupChatName(String filename) {
        for (String name : filename.split("/")) {
            if (GROUP_CHAT_NAMES.contains(name)) {
                return true;
            }
        }
        return false;
    }

    private static Object storagePathOf(AssignmentSubmission submission) {
        Map<String, Object> content = submission.getContent();
        return content == null ? null : content.get("storagePath");
    }

    /** A folder named by a group's id, written the way resolveStorageUid writes it. */
    private static Long groupId(String folder) {
        if (folder == null) {
            return null;
        }
        try {
            long id = Long.parseLong(folder);
            return id > 0 && Long.toString(id).equals(folder) ? id : null;
        } catch (NumberFormatException notAnId) {
            return null;
        }
    }
}
