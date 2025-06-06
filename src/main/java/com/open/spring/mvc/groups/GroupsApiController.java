package com.open.spring.mvc.groups;


import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;


import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
import org.springframework.web.server.ResponseStatusException;


import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonDetailsService;
import com.open.spring.mvc.person.PersonJpaRepository;


import lombok.Getter;


@RestController
@RequestMapping("/api/groups")
public class GroupsApiController {


    @Autowired
    private GroupsJpaRepository groupsRepository;


    @Autowired
    private PersonJpaRepository personRepository;


    // DTO for creating a new group
    @Getter
        public static class GroupDto {
            private List<String> personUids;
            private String name;
            private String period;


            public List<String> getPersonUids() { return personUids; }
            public void setPersonUids(List<String> personUids) { this.personUids = personUids; }


            public String getName() { return name; }
            public void setName(String name) { this.name = name; }


            public String getPeriod() { return period; }
            public void setPeriod(String period) { this.period = period; }
        }




    /**
     * Extract basic info from a Person object to avoid circular references
     */
    private Map<String, Object> getPersonBasicInfo(Person person) {
        Map<String, Object> personInfo = new HashMap<>();
        personInfo.put("id", person.getId());
        personInfo.put("uid", person.getUid());
        personInfo.put("name", person.getName());
        personInfo.put("email", person.getEmail());
        // Add other Person properties as needed, but exclude the group reference
        return personInfo;
    }


    /**
     * Get all groups with their members
     */
    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<List<Map<String, Object>>> getAllGroups(@AuthenticationPrincipal UserDetails userDetails) {
        String uid = userDetails.getUsername();
        Person grader = personRepository.findByUid(uid);
        if (grader == null) {
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN, "You must be a logged in user to retrieve the groups"
            );
        }
        
        List<Groups> groups;
        if (grader.hasRoleWithName("ROLE_TEACHER") || grader.hasRoleWithName("ROLE_ADMIN")) {
            groups = groupsRepository.findAll();
        } else {
            groups = groupsRepository.findGroupsByPersonId(grader.getId());
        }
        
        List<Map<String, Object>> groupsWithMembers = new ArrayList<>();
       
        for (Groups group : groups) {
            Map<String, Object> groupMap = new HashMap<>();
            groupMap.put("id", group.getId());
            groupMap.put("name", group.getName());
            groupMap.put("period", group.getPeriod());
           
            // Extract basic info from each person to avoid serialization issues
            List<Map<String, Object>> membersList = new ArrayList<>();
            for (Person person : group.getGroupMembers()) {
                membersList.add(getPersonBasicInfo(person));
            }
           
            groupMap.put("members", membersList);
            groupsWithMembers.add(groupMap);
        }
       
        return new ResponseEntity<>(groupsWithMembers, HttpStatus.OK);
    }


    /**
     * Get a group by ID
     */
    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> getGroupById(@PathVariable Long id) {
        Optional<Groups> optionalGroup = groupsRepository.findById(id);
        if (optionalGroup.isPresent()) {
            Groups group = optionalGroup.get();
           
            Map<String, Object> groupMap = new HashMap<>();
            groupMap.put("id", group.getId());
           
            // Extract basic info from each person to avoid serialization issues
            List<Map<String, Object>> membersList = new ArrayList<>();
            for (Person person : group.getGroupMembers()) {
                membersList.add(getPersonBasicInfo(person));
            }
           
            groupMap.put("members", membersList);
           
            return new ResponseEntity<>(groupMap, HttpStatus.OK);
        }
        return new ResponseEntity<>(HttpStatus.NOT_FOUND);
    }


    /**
     * Create a new group with multiple people
     */
@PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
@Transactional
public ResponseEntity<Object> createGroup(@RequestBody GroupDto groupDto) {
    try {
        // Create a new group with the provided name and period
        Groups group = new Groups(groupDto.getName(), groupDto.getPeriod(), new ArrayList<>());


        // Save the group first to generate an ID
        Groups savedGroup = groupsRepository.save(group);


        // Add members to the group using personUids
        if (groupDto.getPersonUids() != null) {
            for (String personUid : groupDto.getPersonUids()) {
                Person person = personRepository.findByUid(personUid);
                if (person != null) {
                    savedGroup.addPerson(person);
                } else {
                    System.out.println("Warning: No person found with UID: " + personUid);
                }
            }
        }


        // Save the group again with all members
        Groups finalGroup = groupsRepository.save(savedGroup);


        // Prepare response
        Map<String, Object> response = new HashMap<>();
        response.put("id", finalGroup.getId());
        response.put("name", finalGroup.getName());
        response.put("period", finalGroup.getPeriod());


        List<Map<String, Object>> membersList = new ArrayList<>();
        for (Person person : finalGroup.getGroupMembers()) {
            membersList.add(getPersonBasicInfo(person));
        }
        response.put("members", membersList);


        return new ResponseEntity<>(response, HttpStatus.CREATED);
    } catch (Exception e) {
        return new ResponseEntity<>("Error creating group: " + e.getMessage(), HttpStatus.BAD_REQUEST);
    }
}








    /**
 * Bulk create multiple groups from a list of GroupDto objects.
 *
 * @param groupDtos List of GroupDto objects containing group information
 * @return A ResponseEntity containing information about the created, duplicate, and error groups
 */
@PostMapping("/bulk/create")
@Transactional
public ResponseEntity<Object> bulkCreateGroups(@RequestBody List<GroupDto> groupDtos) {
    List<String> createdGroups = new ArrayList<>();
    List<String> duplicateGroups = new ArrayList<>();
    List<String> errors = new ArrayList<>();


    for (GroupDto groupDto : groupDtos) {
        try {
            // Create a new group with the provided name and period
            Groups group = new Groups(groupDto.getName(), groupDto.getPeriod(), new ArrayList<>());


            // Save the group first to generate an ID
            Groups savedGroup = groupsRepository.save(group);


            // Add members by person UIDs
            for (String personUid : groupDto.getPersonUids()) {
                Person person = personRepository.findByUid(personUid);
                if (person != null) {
                    savedGroup.addPerson(person);
                } else {
                    System.out.println("Warning: No person found with UID: " + personUid);
                }
            }


            // Save the group again with all members added
            savedGroup = groupsRepository.save(savedGroup);


            // Force initialization of group members collection to avoid lazy loading issues
            savedGroup.getGroupMembers().size();


            createdGroups.add(groupDto.getName() + " (Period: " + groupDto.getPeriod() + ")");


        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().toLowerCase().contains("duplicate")) {
                duplicateGroups.add(groupDto.getName() + " (Period: " + groupDto.getPeriod() + ")");
            } else {
                errors.add("Exception occurred for group: " + groupDto.getName()
                        + " (Period: " + groupDto.getPeriod() + ") - " + e.getMessage());
            }
        }
    }


    // Prepare the response map
    Map<String, Object> response = new HashMap<>();
    response.put("created", createdGroups);
    response.put("duplicates", duplicateGroups);
    response.put("errors", errors);


    return new ResponseEntity<>(response, HttpStatus.OK);
}






    /**
     * Bulk extract all groups with their members in a simplified format
     */
    @GetMapping("/bulk/extract")
    public ResponseEntity<List<Map<String, Object>>> bulkExtractGroups() {
        // Fetch all Groups entities from the database
        List<Groups> groups = groupsRepository.findAll();
       
        // Map Groups entities to Map objects
        List<Map<String, Object>> groupsList = new ArrayList<>();
        for (Groups group : groups) {
            Map<String, Object> groupMap = new HashMap<>();
            groupMap.put("id", group.getId());
            groupMap.put("name", group.getName());
            groupMap.put("period", group.getPeriod());
           
            // Extract basic info for each member
            List<Map<String, Object>> membersList = new ArrayList<>();
            for (Person person : group.getGroupMembers()) {
                membersList.add(getPersonBasicInfo(person));
            }
            groupMap.put("members", membersList);
           
            groupsList.add(groupMap);
        }
       
        // Return the list of group maps
        return new ResponseEntity<>(groupsList, HttpStatus.OK);
    }


    /**
     * Add people to an existing group
     */
    @PutMapping("/{id}/addPeople")
    @Transactional
    public ResponseEntity<Object> addPeopleToGroup(@PathVariable Long id, @RequestBody List<Long> personIds) {
        Optional<Groups> optionalGroup = groupsRepository.findById(id);
        if (optionalGroup.isPresent()) {
            Groups group = optionalGroup.get();
           
            boolean changesDetected = false;
            for (Long personId : personIds) {
                Optional<Person> optionalPerson = personRepository.findById(personId);
                if (optionalPerson.isPresent()) {
                    Person person = optionalPerson.get();
                    if (!group.getGroupMembers().contains(person)) {
                        group.addPerson(person);
                        changesDetected = true;
                    }
                }
            }
           
            // Only save if changes were made
            Groups updatedGroup = changesDetected ? groupsRepository.save(group) : group;
           
            // Return the group with its members
            Map<String, Object> response = new HashMap<>();
            response.put("id", updatedGroup.getId());
           
            List<Map<String, Object>> membersList = new ArrayList<>();
            for (Person person : updatedGroup.getGroupMembers()) {
                membersList.add(getPersonBasicInfo(person));
            }
           
            response.put("members", membersList);
           
            return new ResponseEntity<>(response, HttpStatus.OK);
        }
        return new ResponseEntity<>(HttpStatus.NOT_FOUND);
    }


    /**
     * Remove people from a group
     */
    @PutMapping("/{id}/removePeople")
    @Transactional
    public ResponseEntity<Object> removePeopleFromGroup(@PathVariable Long id, @RequestBody List<Long> personIds) {
        Optional<Groups> optionalGroup = groupsRepository.findById(id);
        if (optionalGroup.isPresent()) {
            Groups group = optionalGroup.get();
           
            boolean changesDetected = false;
            for (Long personId : personIds) {
                Optional<Person> optionalPerson = personRepository.findById(personId);
                if (optionalPerson.isPresent()) {
                    Person person = optionalPerson.get();
                    if (group.getGroupMembers().contains(person)) {
                        group.removePerson(person);
                        changesDetected = true;
                    }
                }
            }
           
            // Only save if changes were made
            if (changesDetected) {
                // Save the group which will cascade the changes
                Groups savedGroup = groupsRepository.save(group);
               
                // Now save any persons that were removed from the group
                for (Long personId : personIds) {
                    Optional<Person> optionalPerson = personRepository.findById(personId);
                    if (optionalPerson.isPresent()) {
                        Person person = optionalPerson.get();
                        if (person.getGroups() == null) {
                            personRepository.save(person);
                        }
                    }
                }
               
                Map<String, Object> response = new HashMap<>();
                response.put("id", savedGroup.getId());
               
                List<Map<String, Object>> membersList = new ArrayList<>();
                for (Person person : savedGroup.getGroupMembers()) {
                    membersList.add(getPersonBasicInfo(person));
                }
               
                response.put("members", membersList);
               
                return new ResponseEntity<>(response, HttpStatus.OK);
            } else {
                Map<String, Object> response = new HashMap<>();
                response.put("id", group.getId());
               
                List<Map<String, Object>> membersList = new ArrayList<>();
                for (Person person : group.getGroupMembers()) {
                    membersList.add(getPersonBasicInfo(person));
                }
               
                response.put("members", membersList);
               
                return new ResponseEntity<>(response, HttpStatus.OK);
            }
        }
        return new ResponseEntity<>(HttpStatus.NOT_FOUND);
    }


    /**
     * Delete a group (but not its members)
     */
    @PostMapping("/delete/{id}")
    @Transactional
    public ResponseEntity<Object> deleteGroup(@PathVariable Long id) {
        Optional<Groups> optionalGroup = groupsRepository.findById(id);
        if (optionalGroup.isPresent()) {
            Groups group = optionalGroup.get();
           
            // Unlink all people from this group
            List<Person> members = new ArrayList<>(group.getGroupMembers());
            for (Person person : members) {
                group.removePerson(person);
            }
           
            // Save group first to update all relationship changes
            groupsRepository.save(group);
           
            // Now save each person with their updated null group reference
            for (Person person : members) {
                personRepository.save(person);
            }
           
            // Finally delete the group
            groupsRepository.deleteById(id);
            return new ResponseEntity<>("Group deleted successfully", HttpStatus.OK);
        }
        return new ResponseEntity<>(HttpStatus.NOT_FOUND);
    }


    /**
     * Update Group information
     */
    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<Object> updateGroup(@PathVariable Long id, @RequestBody GroupDto groupDto) {
        Optional<Groups> optionalGroup = groupsRepository.findById(id);
        if (optionalGroup.isPresent()) {
            Groups group = optionalGroup.get();
           
            // Update name and period
            group.setName(groupDto.getName());
            group.setPeriod(groupDto.getPeriod());
           
            // Save the updated group
            Groups updatedGroup = groupsRepository.save(group);
           
            // Prepare response
            Map<String, Object> response = new HashMap<>();
            response.put("id", updatedGroup.getId());
            response.put("name", updatedGroup.getName());
            response.put("period", updatedGroup.getPeriod());
           
            List<Map<String, Object>> membersList = new ArrayList<>();
            for (Person person : updatedGroup.getGroupMembers()) {
                membersList.add(getPersonBasicInfo(person));
            }
            response.put("members", membersList);
           
            return new ResponseEntity<>(response, HttpStatus.OK);
        }
        return new ResponseEntity<>(HttpStatus.NOT_FOUND);
    }


    /**
     * Find groups containing a specific person
     */
    @GetMapping("/person/{personId}")
    @Transactional(readOnly = true)
    public ResponseEntity<List<Map<String, Object>>> getGroupsByPersonId(@PathVariable Long personId) {
        List<Groups> groups = groupsRepository.findGroupsByPersonId(personId);
        List<Map<String, Object>> groupsWithMembers = new ArrayList<>();
       
        for (Groups group : groups) {
            Map<String, Object> groupMap = new HashMap<>();
            groupMap.put("id", group.getId());
           
            // Extract basic info from each person to avoid serialization issues
            List<Map<String, Object>> membersList = new ArrayList<>();
            for (Person person : group.getGroupMembers()) {
                membersList.add(getPersonBasicInfo(person));
            }
           
            groupMap.put("members", membersList);
            groupsWithMembers.add(groupMap);
        }
       
        return new ResponseEntity<>(groupsWithMembers, HttpStatus.OK);
    }
}
