package com.open.spring.mvc.uibuilder;

import org.hibernate.annotations.DynamicUpdate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One UI Builder page: a row of ui_builder_page. It holds up to three copies of the page document
 * (a JSON spec): the draft its editors save, the published copy signed-in members see, and the copy
 * a teacher approved for everyone (anonymous visitors). Specs are stored as text written by
 * Jackson, never as a JSON column, so the same table works on SQLite and MySQL.
 *
 * draftVersion changes only when a draft is saved or restored; every write first takes the row lock
 * (UiBuilderPageJpaRepository.lockForWrite) and then compares it with the version the editor saw.
 * There is no JPA @Version. Ids are plain numbers (no @ManyToOne): owner and lease holder are person
 * ids, groupId a group id. Times are epoch milliseconds. The table is created by
 * UiBuilderPageTableInitializer (ddl-auto is none). @DynamicUpdate writes only changed columns, so
 * renewing a lease doesn't rewrite three specs. public_version is set exactly when public_spec is.
 */
@Entity
@Table(name = "ui_builder_page")
@DynamicUpdate
@Data
@NoArgsConstructor
public class UiBuilderPageEntity {
    public static final String DRAFT = "DRAFT";
    public static final String PUBLISHED = "PUBLISHED";
    public static final String DELETED = "DELETED";
    public static final String SIGNED_IN = "SIGNED_IN";
    public static final String PUBLIC = "PUBLIC";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Null until the first publish, then fixed; a deleted page keeps it so the address is never reused. */
    @Column(length = 64, unique = true)
    private String slug;

    @Column(length = 120, nullable = false)
    private String title;

    @Column(length = 300)
    private String description;

    @Column(length = 120)
    private String byline;

    /** "" or a class group in lower case (csp, csa, ...). */
    @Column(length = 16, nullable = false)
    private String course = "";

    /** The gallery card picture. */
    @Column(length = 500)
    private String image;

    @Column(nullable = false)
    private Long ownerPersonId;

    private Long groupId;

    /** DRAFT, PUBLISHED or DELETED. */
    @Column(length = 16, nullable = false)
    private String status = DRAFT;

    /** SIGNED_IN (OCS members) or PUBLIC (everyone, once a teacher approved a copy). */
    @Column(length = 16, nullable = false)
    private String visibility = SIGNED_IN;

    /** A student asked for PUBLIC; viewers see no change until a teacher approves. */
    @Column(nullable = false)
    private Boolean publicRequested = Boolean.FALSE;

    private Long publicUntil;

    @Column(nullable = false)
    private Long draftVersion = 0L;

    @Column(columnDefinition = "mediumtext", nullable = false)
    private String draftSpec;

    private Long publishedVersion;

    @Column(columnDefinition = "mediumtext")
    private String publishedSpec;

    /** The published version a teacher approved for everyone, and its spec. */
    private Long publicVersion;

    @Column(columnDefinition = "mediumtext")
    private String publicSpec;

    private Long publishedAt;

    @Column(nullable = false)
    private Long createdAt;

    /** When the draft was last saved, and by whom. */
    @Column(nullable = false)
    private Long updatedAt;

    @Column(length = 255)
    private String updatedByName;

    /** The editing lease (who has the page open), renewed by the editor while it stays open. */
    private Long leasePersonId;

    @Column(length = 255)
    private String leaseName;

    private Long leaseExpiresAt;

    private Long deletedAt;

    /**
     * The gallery cards of the published and the approved copy: their title, description, byline
     * and picture as a small JSON object, written with the copy, so the gallery never reads specs.
     * Null for a page published before cards were kept (the gallery then reads that copy once).
     */
    @Column(columnDefinition = "text")
    private String publishedCard;

    @Column(columnDefinition = "text")
    private String publicCard;
}
