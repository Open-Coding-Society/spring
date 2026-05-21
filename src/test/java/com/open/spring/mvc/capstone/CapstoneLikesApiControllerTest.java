package com.open.spring.mvc.capstone;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;

@WebMvcTest(CapstoneLikesApiController.class)
public class CapstoneLikesApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CapstoneLikeService capstoneLikeService;

    @MockBean
    private PersonJpaRepository personRepository;

    @Test
    void getLikesReturns401WhenNotAuthenticated() throws Exception {
        mockMvc.perform(get("/api/capstone/likes/sample-project"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "alice")
    void postLikeReturnsUpdatedCount() throws Exception {
        Person person = new Person();
        person.setUid("alice");
        when(personRepository.findByUid("alice")).thenReturn(person);
        when(capstoneLikeService.likeProject("sample-project", person))
                .thenReturn(new CapstoneLikeResponse("sample-project", 1L, true));

        mockMvc.perform(post("/api/capstone/likes/sample-project"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value("sample-project"))
                .andExpect(jsonPath("$.likes").value(1))
                .andExpect(jsonPath("$.likedByCurrentUser").value(true));
    }

    @Test
    @WithMockUser(username = "alice")
    void deleteLikeReturnsUpdatedCount() throws Exception {
        Person person = new Person();
        person.setUid("alice");
        when(personRepository.findByUid("alice")).thenReturn(person);
        when(capstoneLikeService.unlikeProject("sample-project", person))
                .thenReturn(new CapstoneLikeResponse("sample-project", 0L, false));

        mockMvc.perform(delete("/api/capstone/likes/sample-project"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value("sample-project"))
                .andExpect(jsonPath("$.likes").value(0))
                .andExpect(jsonPath("$.likedByCurrentUser").value(false));
    }
}
