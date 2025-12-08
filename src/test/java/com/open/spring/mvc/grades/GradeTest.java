package com.open.spring.mvc.grades;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class GradeTest {

    @Test
    public void testGradeFields() {
        Grade grade = new Grade();
        String uid = "gh_12345";

        // Test uid setter/getter
        grade.setUid(uid);
        assertEquals(uid, grade.getUid(), "getUid should return set uid");

        // Test course field
        String course = "CSA";
        grade.setCourse(course);
        assertEquals(course, grade.getCourse(), "getCourse should return set course");
    }
}
