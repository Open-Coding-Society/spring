package com.open.spring;

import java.util.List;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import com.open.spring.mvc.groups.GroupChatService;
import com.open.spring.mvc.groups.Groups;
import com.open.spring.mvc.groups.GroupsJpaRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@RequiredArgsConstructor
@Slf4j
public class StartupService {

    private final GroupsJpaRepository groupsRepository;
    private final GroupChatService groupChatService;

    @Bean
    public ApplicationRunner initializeGroupStorage() {
        return args -> {
            // Fetch all existing groups from the database
            List<Groups> groups = groupsRepository.findAll();

            // Ensure S3 storage exists for each group. A group whose storage can't be checked is
            // left as it is until the next start, and the other groups are still checked, so one
            // group can't stop the server from starting.
            for (Groups group : groups) {
                try {
                    groupChatService.ensureGroupStorageExists(group.getName());
                } catch (RuntimeException e) {
                    log.warn("Could not check the chat storage of group {}, so nothing was written to it", group.getName(), e);
                }
            }
        };
    }
}