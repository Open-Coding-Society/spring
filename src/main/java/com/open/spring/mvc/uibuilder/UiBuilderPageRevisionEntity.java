package com.open.spring.mvc.uibuilder;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One earlier copy of a page's draft: a row of ui_builder_page_revision. kind says why it was kept:
 * SAVE (autosaves, grouped: one row per person per ten minutes, see UiBuilderPageService), PUBLISH
 * (the draft as it was published) or RESTORE (the draft after an earlier copy was restored).
 * savedAt is when the row was started; a SAVE row's spec is the last save made into it.
 */
@Entity
@Table(name = "ui_builder_page_revision")
@Data
@NoArgsConstructor
public class UiBuilderPageRevisionEntity {
    public static final String SAVE = "SAVE";
    public static final String PUBLISH = "PUBLISH";
    public static final String RESTORE = "RESTORE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long pageId;

    /** The page's draftVersion this spec had. */
    @Column(nullable = false)
    private Long pageVersion;

    @Column(length = 16, nullable = false)
    private String kind;

    @Column(columnDefinition = "mediumtext", nullable = false)
    private String spec;

    private Long savedByPersonId;

    @Column(length = 255)
    private String savedByName;

    @Column(nullable = false)
    private Long savedAt;
}
