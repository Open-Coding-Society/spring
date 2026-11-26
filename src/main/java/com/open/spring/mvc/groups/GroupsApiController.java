package com.open.spring.mvc.groups;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;
import com.open.spring.mvc.person.RolesJsonConverter;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@RestController
@RequestMapping("/api/groups")
public class GroupsApiController {

    @Autowired
    private GroupsJpaRepository groupsRepository;

    @Autowired
    private PersonJpaRepository personRepository;

    @Autowired
    private GroupChatService groupChatService;

    @Autowired
    private ClassGroupMembershipService classGroupMembershipService;

    @Autowired
    private MentorAccess mentorAccess;

    private static final RolesJsonConverter ROLES = new RolesJsonConverter();

    // ===== DTOs =====
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GroupCreateDto {
        private String name;
        private String period;
        private String course;        
        private List<Long> memberIds;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GroupUpdateDto {
        private String name;
        private String period;
        private String course;        
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BulkGroupCreateDto {
        private List<GroupCreateDto> groups;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ClassMembershipSyncDto {
        private List<String> classes;
    }

    // ===== Helper Methods =====

    /** The signed-in Person, or null when nobody is signed in. */
    private Person currentPerson() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getName())) {
            return null;
        }
        return personRepository.findByUid(auth.getName());
    }

    /**
     * Refuses a mentor the routes that look through or change groups (see MentorRules.manageGroups), or null when
     * the signed-in account may go on.
     */
    private ResponseEntity<Object> mentorManageRefusal() {
        return MentorRules.manageGroups(currentPerson()).map(MentorRules.Refusal::toResponse).orElse(null);
    }

    /**
     * Who manages a group's members. A group has no owner, so its members do, as the Messages
     * page's Members dialog lets them, and so do teachers and admins. A class group's members come
     * from the classes on each student's Profile, so on these routes only teachers and admins
     * change one.
     */
    private boolean managesGroup(Person caller, Groups group) {
        if (caller == null) {
            return false;
        }
        if (isStaff(caller)) {
            return true;
        }
        if (classGroupMembershipService.isClassGroup(group.getName())) {
            return false;
        }
        return groupsRepository.findMembersOf(group.getId()).stream()
                .anyMatch(member -> java.util.Objects.equals(member.getId(), caller.getId()));
    }

    /** Joining and leaving: anyone adds or removes themselves, except in a class group. */
    private boolean joinsOrLeaves(Person caller, Groups group, Person person) {
        return caller != null && person != null && java.util.Objects.equals(caller.getId(), person.getId())
                && !classGroupMembershipService.isClassGroup(group.getName());
    }

    /** True for a teacher or an admin. */
    private static boolean isStaff(Person caller) {
        return caller != null && (caller.hasRole("ROLE_TEACHER") || caller.hasRole("ROLE_ADMIN"));
    }

    private static ResponseEntity<Object> notStaff() {
        return new ResponseEntity<>(
            Map.of("error", "Only a teacher or an admin can change a group's name, period or course"),
            HttpStatus.FORBIDDEN
        );
    }

    private static ResponseEntity<Object> notYourGroup() {
        return new ResponseEntity<>(
            Map.of("error", "Only the group's members, a teacher or an admin can change this group"),
            HttpStatus.FORBIDDEN
        );
    }

    /** Group chat keeps a group's files in S3 under its name, so a name can't add a folder. */
    private static boolean hasSlash(String groupName) {
        return groupName != null && groupName.contains("/");
    }

    private static ResponseEntity<Object> slashInName() {
        return new ResponseEntity<>(Map.of("error", "A group name can't contain \"/\""), HttpStatus.BAD_REQUEST);
    }

    /** The people with these ids, in order, once each, leaving out ids that match no one. */
    private List<Person> findPeople(List<Long> personIds) {
        List<Person> found = new ArrayList<>();
        if (personIds != null) {
            for (Long personId : new java.util.LinkedHashSet<>(personIds)) {
                if (personId != null) {
                    personRepository.findById(personId).ifPresent(found::add);
                }
            }
        }
        return found;
    }

    private Map<String, Object> buildGroupResponse(Groups group) {
        Map<String, Object> groupMap = new LinkedHashMap<>();
        groupMap.put("id", group.getId());
        groupMap.put("name", group.getName());
        groupMap.put("period", group.getPeriod());
        groupMap.put("course", group.getCourse());

        List<Map<String, Object>> membersList = new ArrayList<>();
        List<Object[]> memberRows = groupsRepository.findGroupMembersWithRolesRaw(group.getId());

        for (Object[] row : memberRows) {
            Map<String, Object> member = new LinkedHashMap<>();
            member.put("id", ((Number) row[0]).longValue());
            member.put("uid", (String) row[1]);
            member.put("name", (String) row[2]);
            member.put("email", (String) row[3]);
            // So a page can show a team's mentors without asking about each member
            member.put("mentor", MentorRules.isMentorRoles(ROLES.convertToEntityAttribute((String) row[4])));
            membersList.add(member);
        }

        groupMap.put("members", membersList);
        return groupMap;
    }

    // ===== GET Operations =====

    /**
     * GET /api/groups - Get all groups with their members
     */
    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<?> getAllGroups() {
        ResponseEntity<Object> refusal = mentorManageRefusal();
        if (refusal != null) {
            return refusal;
        }
        try {
            List<Groups> groups = groupsRepository.findAll();
            List<Map<String, Object>> result = new ArrayList<>();

            for (Groups group : groups) {
                result.add(buildGroupResponse(group));
            }

            return new ResponseEntity<>(result, HttpStatus.OK);
        } catch (Exception e) {
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * GET /api/groups/{id} - Get a single group by ID with members
     */
    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public ResponseEntity<?> getGroupById(@PathVariable Long id) {
        try {
            Optional<Groups> groupOpt = groupsRepository.findById(id);
            if (groupOpt.isEmpty()) {
                return new ResponseEntity<>(HttpStatus.NOT_FOUND);
            }
            Optional<MentorRules.Refusal> refusal = mentorAccess.toUse(currentPerson(), groupOpt.get());
            if (refusal.isPresent()) {
                return refusal.get().toResponse();
            }
            return new ResponseEntity<>(buildGroupResponse(groupOpt.get()), HttpStatus.OK);
        } catch (Exception e) {
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * GET /api/groups/person/{personId} - Get all groups containing a specific person
     */
    @GetMapping("/person/{personId}")
    @Transactional(readOnly = true)
    public ResponseEntity<?> getGroupsByPersonId(@PathVariable Long personId) {
        Person viewer = currentPerson();
        if (viewer != null && !personId.equals(viewer.getId())) {
            // A mentor sees only their own groups
            ResponseEntity<Object> refusal = mentorManageRefusal();
            if (refusal != null) {
                return refusal;
            }
        }
        try {
            Optional<Person> personOpt = personRepository.findById(personId);
            if (personOpt.isEmpty()) {
                return new ResponseEntity<>(HttpStatus.NOT_FOUND);
            }

            List<Groups> groups = groupsRepository.findGroupsByPersonId(personId);
            // A mentor's own list holds only the groups they may use (MentorRules), so no page offers a group Spring
            // would refuse them, and they never see the members of one.
            if (MentorRules.isMentor(viewer)) {
                groups = groups.stream().filter(group -> mentorAccess.toUse(viewer, group).isEmpty()).toList();
            }
            List<Map<String, Object>> result = new ArrayList<>();

            for (Groups group : groups) {
                result.add(buildGroupResponse(group));
            }

            return new ResponseEntity<>(result, HttpStatus.OK);
        } catch (Exception e) {
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * GET /api/groups/search?name={searchTerm} - Search groups by name
     * Returns a list of groups whose names contain the search term (case-insensitive)
     */
    @GetMapping("/search")
    @Transactional(readOnly = true)
    public ResponseEntity<?> searchGroupsByName(
            @org.springframework.web.bind.annotation.RequestParam(value = "name", required = true) String searchTerm) {
        ResponseEntity<Object> refusal = mentorManageRefusal();
        if (refusal != null) {
            return refusal;
        }
        try {
            if (searchTerm == null || searchTerm.trim().isEmpty()) {
                return new ResponseEntity<>(
                    new ArrayList<>(),
                    HttpStatus.OK
                );
            }

            List<Groups> groups = groupsRepository.searchByName(searchTerm.trim());
            List<Map<String, Object>> result = new ArrayList<>();

            for (Groups group : groups) {
                result.add(buildGroupResponse(group));
            }

            return new ResponseEntity<>(result, HttpStatus.OK);
        } catch (Exception e) {
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    // ===== POST Operations =====

    /**
     * POST /api/groups - Create a new group with optional initial members
     * Request body: { "name": "groupname", "period": "1", "memberIds": [1, 2, 3] }
     */
    @PostMapping
    @Transactional
    public ResponseEntity<?> createGroup(@RequestBody GroupCreateDto dto) {
        ResponseEntity<Object> mentorRefusal = mentorManageRefusal();
        if (mentorRefusal != null) {
            return mentorRefusal;
        }
        if (hasSlash(dto.getName())) {
            return slashInName();
        }
        try {
            if (dto.getName() == null || dto.getName().isEmpty()) {
                return new ResponseEntity<>(
                    Map.of("error", "Group name is required"),
                    HttpStatus.BAD_REQUEST
                );
            }

            if (groupsRepository.findByName(dto.getName()).isPresent()) {
                return new ResponseEntity<>(
                    Map.of("error", "Group with name '" + dto.getName() + "' already exists"),
                    HttpStatus.CONFLICT
                );
            }

            // A group can start with a mentor only when its maker could have added them (MentorRules.startGroup)
            List<Person> initialMembers = findPeople(dto.getMemberIds());
            Optional<MentorRules.Refusal> refusal = mentorAccess.toStart(currentPerson(), dto.getName(), initialMembers);
            if (refusal.isPresent()) {
                return refusal.get().toResponse();
            }

            Groups group = new Groups();
            group.setName(dto.getName());
            group.setPeriod(dto.getPeriod());
            group.setCourse(dto.getCourse());

            Groups savedGroup = groupsRepository.save(group);

            // Add members if provided
            if (dto.getMemberIds() != null) {
                for (Person person : initialMembers) {
                    savedGroup.addPerson(person);
                }
                savedGroup = groupsRepository.save(savedGroup);
            }

            // Initialize S3 folder structure for the new group
            groupChatService.initGroupStorage(savedGroup.getName());

            return new ResponseEntity<>(buildGroupResponse(savedGroup), HttpStatus.CREATED);
        } catch (Exception e) {
            return new ResponseEntity<>(
                Map.of("error", e.getMessage()),
                HttpStatus.BAD_REQUEST
            );
        }
    }


    /**
     * POST /api/groups/bulk - Bulk create multiple groups
     * Request body: { "groups": [{ "name": "...", "period": "...", "memberIds": [...] }, ...] }
     */
    @PostMapping("/bulk")
    @Transactional
    public ResponseEntity<?> bulkCreateGroups(@RequestBody BulkGroupCreateDto dto) {
        ResponseEntity<Object> mentorRefusal = mentorManageRefusal();
        if (mentorRefusal != null) {
            return mentorRefusal;
        }
        Person actor = currentPerson();
        try {
            List<Map<String, Object>> created = new ArrayList<>();
            List<String> errors = new ArrayList<>();

            if (dto.getGroups() == null || dto.getGroups().isEmpty()) {
                return new ResponseEntity<>(
                    Map.of("error", "No groups provided"),
                    HttpStatus.BAD_REQUEST
                );
            }

            for (GroupCreateDto groupDto : dto.getGroups()) {
                try {
                    if (hasSlash(groupDto.getName())) {
                        errors.add("Group name '" + groupDto.getName() + "' can't contain \"/\"");
                        continue;
                    }
                    if (groupsRepository.findByName(groupDto.getName()).isPresent()) {
                        errors.add("Group with name '" + groupDto.getName() + "' already exists");
                        continue;
                    }

                    List<Person> initialMembers = findPeople(groupDto.getMemberIds());
                    Optional<MentorRules.Refusal> refusal =
                            mentorAccess.toStart(actor, groupDto.getName(), initialMembers);
                    if (refusal.isPresent()) {
                        errors.add("Group '" + groupDto.getName() + "': " + refusal.get().message());
                        continue;
                    }

                    Groups group = new Groups();
                    group.setName(groupDto.getName());
                    group.setPeriod(groupDto.getPeriod());
                    group.setCourse(groupDto.getCourse());

                    Groups savedGroup = groupsRepository.save(group);

                    if (groupDto.getMemberIds() != null) {
                        for (Person person : initialMembers) {
                            savedGroup.addPerson(person);
                        }
                        savedGroup = groupsRepository.save(savedGroup);
                    }

                    // Initialize S3 folder structure for the new group
                    groupChatService.initGroupStorage(savedGroup.getName());

                    created.add(buildGroupResponse(savedGroup));
                } catch (Exception e) {
                    errors.add("Failed to create group '" + groupDto.getName() + "': " + e.getMessage());
                }
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("created", created);
            if (!errors.isEmpty()) {
                response.put("errors", errors);
            }

            return new ResponseEntity<>(response, HttpStatus.OK);
        } catch (Exception e) {
            return new ResponseEntity<>(
                Map.of("error", e.getMessage()),
                HttpStatus.BAD_REQUEST
            );
        }
    }

    // ===== PUT Operations =====

    /**
     * PUT /api/groups/class-memberships - Synchronize the current user's course
     * groups with the classes selected on their profile.
     */
    @PutMapping("/class-memberships")
    public ResponseEntity<?> syncClassMemberships(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody ClassMembershipSyncDto dto) {
        if (userDetails == null) {
            return new ResponseEntity<>(
                Map.of("error", "Authentication is required"),
                HttpStatus.UNAUTHORIZED
            );
        }

        // A mentor may leave every class group (an empty list) but join none.
        boolean joins = dto != null && dto.getClasses() != null && !dto.getClasses().isEmpty();
        if (joins && MentorRules.isMentor(personRepository.findByUid(userDetails.getUsername()))) {
            return MentorRules.Refusal.CLASS_GROUP.toResponse();
        }

        try {
            List<String> memberships = classGroupMembershipService.syncMemberships(
                userDetails.getUsername(),
                dto.getClasses()
            );
            return new ResponseEntity<>(Map.of("groups", memberships), HttpStatus.OK);
        } catch (IllegalArgumentException e) {
            return new ResponseEntity<>(Map.of("error", e.getMessage()), HttpStatus.BAD_REQUEST);
        } catch (java.util.NoSuchElementException e) {
            return new ResponseEntity<>(Map.of("error", e.getMessage()), HttpStatus.NOT_FOUND);
        }
    }

    /**
     * PUT /api/groups/{id} - Update group name and/or period
     * Request body: { "name": "newname", "period": "2" }
     */
    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> updateGroup(
            @PathVariable Long id,
            @RequestBody GroupUpdateDto dto) {
        ResponseEntity<Object> refusal = mentorManageRefusal();
        if (refusal != null) {
            return refusal;
        }
        try {
            Optional<Groups> groupOpt = groupsRepository.findById(id);
            if (groupOpt.isEmpty()) {
                return new ResponseEntity<>(
                    Map.of("error", "Group not found"),
                    HttpStatus.NOT_FOUND
                );
            }

            Groups group = groupOpt.get();
            // Nothing in pages edits a group, and anyone can join a group that isn't a class group and then counts as a member
            if (!isStaff(currentPerson())) {
                return notStaff();
            }
            if (hasSlash(dto.getName())) {
                return slashInName();
            }
            boolean renames = dto.getName() != null && !dto.getName().isEmpty() && !dto.getName().equals(group.getName());
            if (renames && classGroupMembershipService.isClassGroup(group.getName())) {
                return new ResponseEntity<>(Map.of("error", "A class group can't be renamed"), HttpStatus.FORBIDDEN);
            }
            // Profile's class sync, a course's video call room and the assignment course sync find a group by name, so names stay unique
            if (renames
                    && (groupsRepository.findByName(dto.getName()).isPresent()
                        || classGroupMembershipService.isClassGroup(dto.getName()))) {
                return new ResponseEntity<>(
                    Map.of("error", "A group named '" + dto.getName() + "' already exists"),
                    HttpStatus.CONFLICT
                );
            }

            if (dto.getName() != null && !dto.getName().isEmpty()) {
                group.setName(dto.getName());
            }
            if (dto.getPeriod() != null) {
                group.setPeriod(dto.getPeriod());
            }
            if (dto.getCourse() != null) {
                group.setCourse(dto.getCourse());
            }


            Groups updatedGroup = groupsRepository.save(group);
            return new ResponseEntity<>(buildGroupResponse(updatedGroup), HttpStatus.OK);
        } catch (Exception e) {
            return new ResponseEntity<>(
                Map.of("error", e.getMessage()),
                HttpStatus.BAD_REQUEST
            );
        }
    }

    // ===== DELETE Operations =====


    
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteGroup (@PathVariable Long id) {
        ResponseEntity<Object> refusal = mentorManageRefusal();
        if (refusal != null) {
            return refusal;
        }
        Person caller = currentPerson();
        if (caller == null || !caller.hasRole("ROLE_ADMIN")) {
            return new ResponseEntity<>(Map.of("error", "Only an admin can delete a group"), HttpStatus.FORBIDDEN);
        }
        try {
            Optional<Groups> groupOpt = groupsRepository.findById(id);
            if (groupOpt.isEmpty()) {
                return new ResponseEntity<>(
                    Map.of("error", "Group not found"),
                    HttpStatus.NOT_FOUND
                );
            }
            // A class group's members come from the classes on each student's Profile; the Messages page never offers to delete one
            if (classGroupMembershipService.isClassGroup(groupOpt.get().getName())) {
                return new ResponseEntity<>(Map.of("error", "A class group can't be deleted"), HttpStatus.FORBIDDEN);
            }

            groupsRepository.deleteById(id);
            return new ResponseEntity<>(
                Map.of("message", "Group deleted successfully"),
                HttpStatus.OK
            );
        } catch (Exception e) {
            return new ResponseEntity<>(
                Map.of("error", e.getMessage()),
                HttpStatus.BAD_REQUEST
            );
        }
    }



    /**
     * DELETE /api/groups/{id}/members/{personId} - Remove a single person from a group
     */
    @DeleteMapping("/{id}/members/{personId}")
    @Transactional
    public ResponseEntity<?> removePersonFromGroup(
            @PathVariable Long id,
            @PathVariable Long personId) {
        try {
            Optional<Groups> groupOpt = groupsRepository.findById(id);
            if (groupOpt.isEmpty()) {
                return new ResponseEntity<>(
                    Map.of("error", "Group not found"),
                    HttpStatus.NOT_FOUND
                );
            }

            Optional<Person> personOpt = personRepository.findById(personId);
            if (personOpt.isEmpty()) {
                return new ResponseEntity<>(
                    Map.of("error", "Person not found"),
                    HttpStatus.NOT_FOUND
                );
            }

            Groups group = groupOpt.get();
            Person person = personOpt.get();

            // The team manages its mentors; a mentor may only leave
            Optional<MentorRules.Refusal> refusal = mentorAccess.toRemove(currentPerson(), group, person);
            if (refusal.isPresent()) {
                return refusal.get().toResponse();
            }

            Person caller = currentPerson();
            if (!managesGroup(caller, group) && !joinsOrLeaves(caller, group, person)) {
                return notYourGroup();
            }

            if (!group.getGroupMembers().contains(person)) {
                return new ResponseEntity<>(
                    Map.of("error", "Person not in group"),
                    HttpStatus.CONFLICT
                );
            }

            group.removePerson(person);
            groupsRepository.save(group);

            return new ResponseEntity<>(buildGroupResponse(group), HttpStatus.OK);
        } catch (Exception e) {
            return new ResponseEntity<>(
                Map.of("error", e.getMessage()),
                HttpStatus.BAD_REQUEST
            );
        }
    }

    /**
     * POST /api/groups/{id}/members/{personId} - Add a single person to a group
     */
    @PostMapping("/{id}/members/{personId}")
    @Transactional
    public ResponseEntity<?> addPersonToGroup(
            @PathVariable Long id,
            @PathVariable Long personId) {
        try {
            Optional<Groups> groupOpt = groupsRepository.findById(id);
            if (groupOpt.isEmpty()) {
                return new ResponseEntity<>(
                    Map.of("error", "Group not found"),
                    HttpStatus.NOT_FOUND
                );
            }

            Optional<Person> personOpt = personRepository.findById(personId);
            if (personOpt.isEmpty()) {
                return new ResponseEntity<>(
                    Map.of("error", "Person not found"),
                    HttpStatus.NOT_FOUND
                );
            }

            Groups group = groupOpt.get();
            Person person = personOpt.get();

            // A team member or a teacher adds a mentor, and only to a group a mentor may use
            Optional<MentorRules.Refusal> refusal = mentorAccess.toAdd(currentPerson(), group, person);
            if (refusal.isPresent()) {
                return refusal.get().toResponse();
            }

            Person caller = currentPerson();
            if (!managesGroup(caller, group) && !joinsOrLeaves(caller, group, person)) {
                return notYourGroup();
            }

            if (group.getGroupMembers().contains(person)) {
                return new ResponseEntity<>(
                    Map.of("error", "Person already in group"),
                    HttpStatus.CONFLICT
                );
            }

            group.addPerson(person);
            groupsRepository.save(group);

            return new ResponseEntity<>(buildGroupResponse(group), HttpStatus.OK);
        } catch (Exception e) {
            return new ResponseEntity<>(
                Map.of("error", e.getMessage()),
                HttpStatus.BAD_REQUEST
            );
        }
    }
    // ===== Group Grades (JSON blob) =====

    @GetMapping("/{id}/grades")
    @Transactional(readOnly = true)
    public ResponseEntity<?> getGroupGrades(@PathVariable Long id) {
        ResponseEntity<Object> mentorRefusal = mentorManageRefusal();
        if (mentorRefusal != null) {
            return mentorRefusal;
        }
        Optional<Groups> groupOpt = groupsRepository.findById(id);
        if (groupOpt.isEmpty()) return new ResponseEntity<>(HttpStatus.NOT_FOUND);

        return new ResponseEntity<>(groupOpt.get().getGradesJson(), HttpStatus.OK);
    }

    /**
     * Replaces the entire group grades blob.
     * Body example: [ {"assignment":"HW1","score":95,"course":"CSA"}, ... ]
     */
    @PutMapping("/{id}/grades")
    @Transactional
    public ResponseEntity<?> putGroupGrades(@PathVariable Long id,
                                        @RequestBody List<Map<String, Object>> grades) {
        ResponseEntity<Object> mentorRefusal = mentorManageRefusal();
        if (mentorRefusal != null) {
            return mentorRefusal;
        }
        Optional<Groups> groupOpt = groupsRepository.findById(id);
        if (groupOpt.isEmpty()) return new ResponseEntity<>(HttpStatus.NOT_FOUND);

        
        for (Map<String, Object> grade : grades) {
            grade.put("type", "GROUP");
        }

        Groups group = groupOpt.get();
        group.setGradesJson(grades != null ? grades : new ArrayList<>());
        groupsRepository.save(group);

        return new ResponseEntity<>(group.getGradesJson(), HttpStatus.OK);
    }

    @PostMapping("/{id}/grades")
    @Transactional
    public ResponseEntity<?> addGroupGrade(@PathVariable Long id,
                                        @RequestBody Map<String, Object> gradeEntry) {
        ResponseEntity<Object> mentorRefusal = mentorManageRefusal();
        if (mentorRefusal != null) {
            return mentorRefusal;
        }
        Optional<Groups> groupOpt = groupsRepository.findById(id);
        if (groupOpt.isEmpty()) return new ResponseEntity<>(HttpStatus.NOT_FOUND);

        Groups group = groupOpt.get();
        if (group.getGradesJson() == null) group.setGradesJson(new ArrayList<>());

        // --- Build a canonical group-grade entry (so group + persons get same schema) ---
        Map<String, Object> canonical = new LinkedHashMap<>(gradeEntry);

        // REQUIRED: you should include assignmentId in request, because upsert logic depends on it
        // canonical.get("assignmentId") should exist

        canonical.put("type", "GROUP");
        canonical.put("origin", "GROUP");
        canonical.put("groupId", group.getId());
        canonical.put("groupName", group.getName());
        canonical.put("period", group.getPeriod());
        canonical.put("course", group.getCourse());

        // --- 1) Save into GROUP's gradesJson (use upsert to avoid duplicates) ---
        upsertGradeEntry(group.getGradesJson(), canonical);
        groupsRepository.save(group);

        // --- 2) Propagate into each MEMBER's Person.gradesJson ---
        List<Object[]> memberRows = groupsRepository.findGroupMembersRaw(group.getId());

        List<Long> memberIds = new ArrayList<>();
        for (Object[] row : memberRows) {
            memberIds.add(((Number) row[0]).longValue());
        }

        List<Person> members = personRepository.findAllById(memberIds);

        for (Person p : members) {
            List<Map<String, Object>> grades =
                (p.getGradesJson() == null) ? new ArrayList<>() : new ArrayList<>(p.getGradesJson());

            Map<String, Object> perPerson = new LinkedHashMap<>(canonical);
            upsertGradeEntry(grades, perPerson);

            // IMPORTANT: setter triggers Hibernate dirty checking
            p.setGradesJson(grades);
        }

        personRepository.saveAll(members);


        // Return something useful
        return new ResponseEntity<>(
            Map.of(
                "groupId", group.getId(),
                "updatedMembers", members.size(),
                "groupGrades", group.getGradesJson()
            ),
            HttpStatus.OK
        );
    }


    /**
     * Clears the group grades blob.
     */
    @DeleteMapping("/{id}/grades")
    @Transactional
    public ResponseEntity<?> clearGroupGrades(@PathVariable Long id) {
        ResponseEntity<Object> mentorRefusal = mentorManageRefusal();
        if (mentorRefusal != null) {
            return mentorRefusal;
        }
        Optional<Groups> groupOpt = groupsRepository.findById(id);
        if (groupOpt.isEmpty()) return new ResponseEntity<>(HttpStatus.NOT_FOUND);

        Groups group = groupOpt.get();
        group.setGradesJson(new ArrayList<>());
        groupsRepository.save(group);

        return new ResponseEntity<>(HttpStatus.NO_CONTENT);
    }
    private static String makeGroupGradeKey(Long groupId, String assignmentId) {
        return "GROUP:" + groupId + ":" + assignmentId;
    }

    private static void upsertGrade(List<Map<String, Object>> grades, Map<String, Object> newEntry) {
        String key = (String) newEntry.get("groupGradeKey");
        if (key == null) return;

        for (int i = 0; i < grades.size(); i++) {
            Object existingKey = grades.get(i).get("groupGradeKey");
            if (key.equals(existingKey)) {
                grades.set(i, newEntry); // replace
                return;
            }
        }
        grades.add(newEntry); // insert if not found
    }

    private static void removeGroupGrade(List<Map<String, Object>> grades, Long groupId, String assignmentId) {
        String key = makeGroupGradeKey(groupId, assignmentId);
        grades.removeIf(g -> key.equals(g.get("groupGradeKey")));
    }

    private static void removeAllGroupGradesForGroup(List<Map<String, Object>> grades, Long groupId) {
        String prefix = "GROUP:" + groupId + ":";
        grades.removeIf(g -> {
            Object k = g.get("groupGradeKey");
            return (k instanceof String) && ((String) k).startsWith(prefix);
        });
    }

    private void upsertGradeEntry(List<Map<String, Object>> grades, Map<String, Object> incoming) {
        if (grades == null) return;

        String assignmentId = incoming.get("assignmentId") != null ? incoming.get("assignmentId").toString() : null;
        String groupId = incoming.get("groupId") != null ? incoming.get("groupId").toString() : null;

        if (assignmentId == null) {
            // if you want, throw instead:
            // throw new IllegalArgumentException("assignmentId is required");
            grades.add(incoming);
            return;
        }

        // Find existing entry with same assignmentId + groupId (for GROUP-origin grades)
        for (int i = 0; i < grades.size(); i++) {
            Map<String, Object> existing = grades.get(i);

            String existingAssignmentId = existing.get("assignmentId") != null ? existing.get("assignmentId").toString() : null;
            String existingGroupId = existing.get("groupId") != null ? existing.get("groupId").toString() : null;

            if (assignmentId.equals(existingAssignmentId) &&
                ((groupId == null && existingGroupId == null) || (groupId != null && groupId.equals(existingGroupId)))) {
                grades.set(i, incoming); // replace
                return;
            }
        }

        grades.add(incoming); // insert
    }


}
