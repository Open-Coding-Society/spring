package com.open.spring.mvc.capstone;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Response payload for capstone like status.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CapstoneLikeResponse {

    private String projectId;
    private long likes;
    private boolean likedByCurrentUser;
}
