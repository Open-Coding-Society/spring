package com.open.spring.mvc.uibuilder;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Reads and writes ui_builder_page_revision. Lists use RevisionRow, so they never load the specs. */
public interface UiBuilderPageRevisionJpaRepository extends JpaRepository<UiBuilderPageRevisionEntity, Long> {

    /** A revision without its spec. */
    interface RevisionRow {
        Long getId();
        Long getPageVersion();
        String getKind();
        String getSavedByName();
        Long getSavedAt();
    }

    /** Newest first. */
    List<RevisionRow> findByPageIdOrderByIdDesc(Long pageId);

    /** The page's newest revision of any kind. */
    Optional<UiBuilderPageRevisionEntity> findFirstByPageIdOrderByIdDesc(Long pageId);

    Optional<UiBuilderPageRevisionEntity> findByIdAndPageId(Long id, Long pageId);

    /** One DELETE statement, rather than loading every spec first. */
    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM UiBuilderPageRevisionEntity r WHERE r.pageId = :pageId")
    int deleteByPageId(@Param("pageId") Long pageId);
}
