package com.nighthawk.spring_portfolio.mvc.groups;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.nighthawk.spring_portfolio.mvc.person.Person;
import com.nighthawk.spring_portfolio.mvc.person.PersonJpaRepository;

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
    public ResponseEntity<List<Map<String, Object>>> getAllGroups() {
        List<Groups> groups = groupsRepository.findAll();
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
            
            // Add members to the group
            for (String personId : groupDto.getPersonUids()) {
                Person person = personRepository.findByUid(personId);
                if (person != null) {
                    savedGroup.addPerson(person);
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
    @DeleteMapping("/{id}")
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