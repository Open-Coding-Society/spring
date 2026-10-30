package com.open.spring.mvc.uibuilder;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Reads and writes ui_builder_page. Lists use the projections below, so a list of pages never
 * loads their specs (up to three 200 000-character documents per row); the gallery reads only
 * the small card of the copy it shows.
 */
public interface UiBuilderPageJpaRepository extends JpaRepository<UiBuilderPageEntity, Long> {

    /** A page's columns for PageSummary, without its specs. */
    interface PageRow {
        Long getId();
        String getSlug();
        String getTitle();
        String getDescription();
        String getByline();
        String getCourse();
        String getImage();
        Long getOwnerPersonId();
        Long getGroupId();
        String getStatus();
        String getVisibility();
        Boolean getPublicRequested();
        Long getPublicUntil();
        Long getDraftVersion();
        Long getPublishedVersion();
        Long getPublicVersion();
        Long getPublishedAt();
        Long getUpdatedAt();
        String getUpdatedByName();
        Long getLeasePersonId();
        String getLeaseName();
        Long getLeaseExpiresAt();
    }

    /** A published page as signed-in members see it in the gallery: its card, and whether its approval is live. */
    interface PublishedRow {
        Long getId();
        String getSlug();
        String getCourse();
        String getVisibility();
        Long getPublicVersion();
        Long getPublicUntil();
        Long getPublishedAt();
        String getPublishedCard();
    }

    /** A page's approved copy, as anonymous visitors see it in the gallery. */
    interface PublicRow {
        Long getId();
        String getSlug();
        String getCourse();
        Long getPublishedAt();
        String getPublicCard();
    }

    /** A person's or group's id and display name. */
    interface NameRow {
        Long getId();
        String getName();
    }

    /**
     * Display names of people, read without loading their accounts: a Person loads several other
     * tables eagerly, and a write transaction would then also check all of them for changes.
     */
    @Query("SELECT p.id AS id, p.name AS name FROM Person p WHERE p.id IN :ids")
    List<NameRow> findPersonNames(@Param("ids") Collection<Long> ids);

    @Query("SELECT g.id AS id, g.name AS name FROM Groups g WHERE g.id IN :ids")
    List<NameRow> findGroupNames(@Param("ids") Collection<Long> ids);

    /**
     * Takes the page's row lock with a no-op UPDATE. It must be the first statement of every write:
     * SQLite has no SELECT ... FOR UPDATE, and a transaction that reads before it writes fails with
     * SQLITE_BUSY if another write commits in between. Returns 0 when the page doesn't exist or was
     * deleted.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE UiBuilderPageEntity p SET p.draftVersion = p.draftVersion WHERE p.id = :id AND p.deletedAt IS NULL")
    int lockForWrite(@Param("id") long id);

    Optional<UiBuilderPageEntity> findByIdAndDeletedAtIsNull(Long id);

    /** Deleted pages count: their slug stays reserved. */
    boolean existsBySlug(String slug);

    Optional<UiBuilderPageEntity> findBySlugAndStatusAndDeletedAtIsNull(String slug, String status);

    List<PageRow> findByOwnerPersonIdAndDeletedAtIsNull(Long ownerPersonId);

    long countByOwnerPersonIdAndDeletedAtIsNull(Long ownerPersonId);

    List<PageRow> findByIdInAndDeletedAtIsNull(Collection<Long> ids);

    List<PageRow> findByCourseAndDeletedAtIsNullOrderByUpdatedAtDescIdDesc(String course, Limit limit);

    List<PageRow> findByDeletedAtIsNullOrderByUpdatedAtDescIdDesc(Limit limit);

    List<PublishedRow> findByStatusAndDeletedAtIsNullOrderByPublishedAtDescIdDesc(String status, Limit limit);

    List<PublishedRow> findByStatusAndCourseAndDeletedAtIsNullOrderByPublishedAtDescIdDesc(String status, String course, Limit limit);

    /** Pages approved for everyone whose approval hasn't run out, newest first. */
    @Query("SELECT p.id AS id, p.slug AS slug, p.course AS course, p.publishedAt AS publishedAt, p.publicCard AS publicCard"
            + " FROM UiBuilderPageEntity p WHERE p.status = 'PUBLISHED' AND p.deletedAt IS NULL"
            + " AND p.visibility = 'PUBLIC' AND p.publicSpec IS NOT NULL"
            + " AND (p.publicUntil IS NULL OR p.publicUntil > :now)"
            + " AND (:course IS NULL OR p.course = :course)"
            + " ORDER BY p.publishedAt DESC, p.id DESC")
    List<PublicRow> findPublicGallery(@Param("course") String course, @Param("now") long now, Limit limit);

    /** One page's published copy, for a gallery card the page doesn't have yet (published before cards were kept). */
    @Query("SELECT p.publishedSpec FROM UiBuilderPageEntity p WHERE p.id = :id")
    String findPublishedSpecById(@Param("id") long id);

    /** One page's approved copy, for the same reason. */
    @Query("SELECT p.publicSpec FROM UiBuilderPageEntity p WHERE p.id = :id")
    String findPublicSpecById(@Param("id") long id);

    /**
     * Approves the current published copy of every published page of a course for everyone, in one
     * statement (the teacher's "make every published page public until ..." before showcase night).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE UiBuilderPageEntity p SET p.visibility = 'PUBLIC', p.publicSpec = p.publishedSpec,"
            + " p.publicVersion = p.publishedVersion, p.publicCard = p.publishedCard, p.publicRequested = false,"
            + " p.publicUntil = :until WHERE p.status = 'PUBLISHED' AND p.course = :course AND p.deletedAt IS NULL")
    int approveCourse(@Param("course") String course, @Param("until") Long until);
}
