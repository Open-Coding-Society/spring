package com.open.spring.mvc.uibuilder;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A person who may edit a page besides its owner and the teachers: a row of ui_builder_page_editor
 * (UNIQUE page_id, person_id, created by UiBuilderPageTableInitializer). The rows are copied from
 * the page's group when its owner or a teacher sets or refreshes the group, and are never looked up
 * from the group at request time, so joining a group later grants nothing until someone refreshes.
 */
@Entity
@Table(name = "ui_builder_page_editor")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UiBuilderPageEditorEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long pageId;

    @Column(nullable = false)
    private Long personId;
}
