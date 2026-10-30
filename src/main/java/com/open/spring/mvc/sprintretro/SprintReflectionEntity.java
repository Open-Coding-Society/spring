package com.open.spring.mvc.sprintretro;

import java.time.LocalDateTime;

import org.hibernate.annotations.DynamicUpdate;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.open.spring.mvc.person.Person;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One student's reflection on one sprint: their own ranks and written reflections, and the
 * teacher's ranks and comments beside them, each stored as JSON.
 *
 * The student and the teacher write different columns. DynamicUpdate makes an UPDATE name only
 * the columns that changed, so a teacher saving feedback never overwrites a student's save that
 * happened in between, and the other way round.
 */
@Data
@NoArgsConstructor
@DynamicUpdate
@Entity(name = "SprintReflection")
@Table(name = "sprint_reflection", uniqueConstraints = @UniqueConstraint(columnNames = {"sprint_id", "person_id"}))
public class SprintReflectionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sprint_id", nullable = false)
    @JsonIgnore
    private SprintRetroEntity sprint;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "person_id", nullable = false)
    @JsonIgnore
    private Person person;

    /** The student's part as JSON, in the shape of SprintRetroService.StudentPart; null until they save. */
    @Column(columnDefinition = "text")
    private String studentData;

    private LocalDateTime updatedAt;

    /** The teacher's part as JSON, in the shape of SprintRetroService.TeacherPart; null until a teacher saves. */
    @Column(columnDefinition = "text")
    private String teacherData;

    private LocalDateTime teacherUpdatedAt;

    /** The teacher or admin who last saved feedback. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "teacher_id")
    @JsonIgnore
    private Person teacher;
}
