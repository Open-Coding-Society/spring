package com.open.spring.mvc.groups;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.open.spring.mvc.person.Person;

/**
 * Loads what MentorRules needs about a group (its members, and whether it is a class group) and asks the rules. A
 * request from someone who isn't a mentor, about someone who isn't a mentor, is answered without loading anything,
 * since the rules leave those as they were.
 */
@Service
public class MentorAccess {

    private final GroupsJpaRepository groups;
    private final ClassGroupMembershipService classGroups;

    public MentorAccess(GroupsJpaRepository groups, ClassGroupMembershipService classGroups) {
        this.groups = groups;
        this.classGroups = classGroups;
    }

    /** Whether person may read the group, post in its chat and share its files. */
    public Optional<MentorRules.Refusal> toUse(Person person, Groups group) {
        if (!MentorRules.isMentor(person)) {
            return Optional.empty();
        }
        return MentorRules.useGroup(person, isClassGroup(group), membersOf(group));
    }

    /** True when the rules refuse person the group with this id. Only a mentor is refused, by a group that exists. */
    public boolean refusesGroup(Person person, Long groupId) {
        if (!MentorRules.isMentor(person) || groupId == null) {
            return false;
        }
        return groups.findById(groupId).map(group -> toUse(person, group).isPresent()).orElse(false);
    }

    /** Whether actor may add newMember to the group. */
    public Optional<MentorRules.Refusal> toAdd(Person actor, Groups group, Person newMember) {
        if (!MentorRules.isMentor(actor) && !MentorRules.isMentor(newMember)) {
            return Optional.empty();
        }
        return MentorRules.addMember(actor, newMember, isClassGroup(group), membersOf(group));
    }

    /** Whether actor may take member out of the group. */
    public Optional<MentorRules.Refusal> toRemove(Person actor, Groups group, Person member) {
        if (!MentorRules.isMentor(actor) && !MentorRules.isMentor(member)) {
            return Optional.empty();
        }
        return MentorRules.removeMember(actor, member, membersOf(group));
    }

    /** Whether actor may make a group with this name that starts with these members. */
    public Optional<MentorRules.Refusal> toStart(Person actor, String groupName, Collection<Person> members) {
        return MentorRules.startGroup(actor, classGroups.isClassGroup(groupName), members);
    }

    private boolean isClassGroup(Groups group) {
        return classGroups.isClassGroup(group.getName());
    }

    private List<Person> membersOf(Groups group) {
        return groups.findMembersOf(group.getId());
    }
}
