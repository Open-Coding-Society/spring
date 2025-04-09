package com.nighthawk.spring_portfolio.mvc.forum.forumComments;

import java.time.LocalDateTime;

import com.nighthawk.spring_portfolio.mvc.forum.Forum;

import jakarta.persistence.Column;
import jakarta.persistence.Entity; // Import the Forum entity
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Entity
@NoArgsConstructor
@AllArgsConstructor
public class forumComments {  

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String author;

    @Column(nullable = false, length = 1000)
    private String comment;

    @Column(nullable = false)
    private LocalDateTime timestamp = LocalDateTime.now();  

    @ManyToOne(fetch = FetchType.EAGER)  
    @JoinColumn(name = "forum_id", referencedColumnName = "id", nullable = false)  
    private Forum forum;  


    // Parameterized Constructor (Lombok handles others)
    public forumComments(String author, String comment,Forum forum) {
        this.author = author;
        this.comment = comment;
        this.timestamp = LocalDateTime.now();
        this.forum = forum;
    }
}
