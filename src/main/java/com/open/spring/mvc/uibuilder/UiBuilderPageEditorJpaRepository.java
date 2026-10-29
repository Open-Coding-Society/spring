package com.open.spring.mvc.uibuilder;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Reads and writes ui_builder_page_editor. */
public interface UiBuilderPageEditorJpaRepository extends JpaRepository<UiBuilderPageEditorEntity, Long> {

    boolean existsByPageIdAndPersonId(Long pageId, Long personId);

    List<UiBuilderPageEditorEntity> findByPersonId(Long personId);

    /** One DELETE statement; the page being written stays attached to the session. */
    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM UiBuilderPageEditorEntity e WHERE e.pageId = :pageId")
    int deleteByPageId(@Param("pageId") Long pageId);
}
