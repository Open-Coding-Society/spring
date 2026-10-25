package com.open.spring.mvc.assignments;

/** Where a submission's effective grade comes from. */
public enum GradeSource {
    /** Not graded yet (or the current revision hasn't been graded). */
    NONE,
    /** The AI grade is effective. */
    AI,
    /** A teacher, student teacher or assignment creator overrode the grade by hand. */
    MANUAL
}
