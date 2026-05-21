package com.open.spring.mvc.capstone;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.open.spring.mvc.person.Person;

@Service
@Transactional
public class CapstoneLikeService {

    @Autowired
    private CapstoneLikeJpaRepository likeRepository;

    public static String normaliseKey(String projectId) {
        if (projectId == null) {
            return "";
        }
        String normalized = projectId.trim().toLowerCase(java.util.Locale.ROOT);
        normalized = normalized.replaceAll("[^a-z0-9]+", "-");
        normalized = normalized.replaceAll("(^-|-$)", "");
        return normalized;
    }

    public CapstoneLikeResponse getLikeStatus(String projectId, Person currentUser) {
        String normalizedProjectId = normaliseKey(projectId);
        long likes = likeRepository.countByProjectId(normalizedProjectId);
        boolean likedByCurrentUser = currentUser != null && likeRepository.existsByProjectIdAndPerson(normalizedProjectId, currentUser);
        return new CapstoneLikeResponse(normalizedProjectId, likes, likedByCurrentUser);
    }

    public CapstoneLikeResponse likeProject(String projectId, Person currentUser) {
        String normalizedProjectId = normaliseKey(projectId);
        if (!likeRepository.existsByProjectIdAndPerson(normalizedProjectId, currentUser)) {
            CapstoneLike like = new CapstoneLike(normalizedProjectId, currentUser);
            likeRepository.save(like);
        }
        long likes = likeRepository.countByProjectId(normalizedProjectId);
        return new CapstoneLikeResponse(normalizedProjectId, likes, true);
    }

    public CapstoneLikeResponse unlikeProject(String projectId, Person currentUser) {
        String normalizedProjectId = normaliseKey(projectId);
        if (likeRepository.existsByProjectIdAndPerson(normalizedProjectId, currentUser)) {
            likeRepository.deleteByProjectIdAndPerson(normalizedProjectId, currentUser);
        }
        long likes = likeRepository.countByProjectId(normalizedProjectId);
        return new CapstoneLikeResponse(normalizedProjectId, likes, false);
    }
}
