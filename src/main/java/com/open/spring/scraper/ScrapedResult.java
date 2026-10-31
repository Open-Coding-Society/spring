package com.open.spring.scraper;

import java.time.LocalDateTime;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A scrape run a student chose to keep, via POST /api/scraper/results. A run from
 * POST /api/scraper/run is never saved on its own - saving is a separate, explicit action
 * (students re-run many times while debugging selectors, and auto-saving every attempt would
 * just fill this table with broken test runs).
 *
 * personId/personUid are both stored (not just a Person foreign key) so listing a student's
 * saved results never needs a join back to Person, matching the pattern already used by
 * Adventure and Games (see ModelInit's buildAdventureTableSql/buildGamesTableSql).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "scraped_result")
public class ScrapedResult {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long personId;
    private String personUid;

    /** Student-given name for this saved run, e.g. "Music books - first try". */
    private String label;

    /** The url that was scraped, denormalized from requestJson for cheap list-view display. */
    private String sourceUrl;

    /** Item count, denormalized from resultJson for the same reason. */
    private Integer itemCount;

    /** The exact ScrapeRequest JSON that produced this result. */
    @Lob
    private String requestJson;

    /** The exact ScrapeResponse JSON (items + meta) returned for this run. */
    @Lob
    private String resultJson;

    private LocalDateTime createdAt;
}
