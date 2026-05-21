package com.open.spring.mvc.capstone;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "capstone_projects")
public class CapstoneProject {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String title;

    private String subtitle;

    @Column(columnDefinition = "text")
    private String description;

    @Column(columnDefinition = "text")
    private String about;

    private String courseCode;
    private String status;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "capstone_tech", joinColumns = @JoinColumn(name = "capstone_project_id"))
    @Column(name = "tech")
    private List<String> tech = new ArrayList<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "capstone_team_members", joinColumns = @JoinColumn(name = "capstone_project_id"))
    @Column(name = "team_members")
    private List<String> teamMembers = new ArrayList<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "capstone_key_points", joinColumns = @JoinColumn(name = "capstone_project_id"))
    @Column(name = "key_points")
    private List<String> keyPoints = new ArrayList<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "capstone_impact", joinColumns = @JoinColumn(name = "capstone_project_id"))
    @Column(name = "impact")
    private List<String> impact = new ArrayList<>();

    private String pageUrl;
    private String frontendUrl;
    private String backendUrl;

    @Column(columnDefinition = "text")
    private String imageUrl;

    private LocalDateTime createdAt;
}
