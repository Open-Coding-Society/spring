package com.open.spring.mvc.capstone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.open.spring.mvc.person.Person;

@ExtendWith(MockitoExtension.class)
public class CapstoneLikeServiceTest {

    @Mock
    private CapstoneLikeJpaRepository likeRepository;

    @InjectMocks
    private CapstoneLikeService likeService;

    private Person user;

    @BeforeEach
    void setUp() {
        user = new Person();
        user.setUid("alice");
    }

    @Test
    void likeProjectCreatesLikeWhenNotAlreadyLiked() {
        when(likeRepository.existsByProjectIdAndPerson("sample-project", user)).thenReturn(false);
        when(likeRepository.countByProjectId("sample-project")).thenReturn(1L);
        when(likeRepository.save(any(CapstoneLike.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CapstoneLikeResponse response = likeService.likeProject("Sample Project", user);

        assertEquals("sample-project", response.getProjectId());
        assertEquals(1L, response.getLikes());
        assertTrue(response.isLikedByCurrentUser());
        verify(likeRepository, times(1)).save(any(CapstoneLike.class));
    }

    @Test
    void likeProjectDoesNotDuplicateExistingLike() {
        when(likeRepository.existsByProjectIdAndPerson("sample-project", user)).thenReturn(true);
        when(likeRepository.countByProjectId("sample-project")).thenReturn(1L);

        CapstoneLikeResponse response = likeService.likeProject("Sample Project", user);

        assertEquals("sample-project", response.getProjectId());
        assertEquals(1L, response.getLikes());
        assertTrue(response.isLikedByCurrentUser());
        verify(likeRepository, never()).save(any(CapstoneLike.class));
    }

    @Test
    void unlikeProjectDeletesExistingLike() {
        when(likeRepository.existsByProjectIdAndPerson("sample-project", user)).thenReturn(true);
        when(likeRepository.countByProjectId("sample-project")).thenReturn(0L);

        CapstoneLikeResponse response = likeService.unlikeProject("Sample Project", user);

        assertEquals("sample-project", response.getProjectId());
        assertEquals(0L, response.getLikes());
        assertEquals(false, response.isLikedByCurrentUser());
        verify(likeRepository, times(1)).deleteByProjectIdAndPerson("sample-project", user);
    }

    @Test
    void getLikeStatusReturnsCountAndUserLikeFlag() {
        when(likeRepository.countByProjectId("sample-project")).thenReturn(2L);
        when(likeRepository.existsByProjectIdAndPerson("sample-project", user)).thenReturn(true);

        CapstoneLikeResponse response = likeService.getLikeStatus("Sample Project", user);

        assertEquals("sample-project", response.getProjectId());
        assertEquals(2L, response.getLikes());
        assertTrue(response.isLikedByCurrentUser());
    }
}
