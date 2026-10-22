package com.alexs.jobagent.source.repository;

import com.alexs.jobagent.source.entity.SourceItemEntity;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SourceItemRepository {
    private final JdbcTemplate jdbc;

    public SourceItemRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Long insertIfAbsent(SourceItemEntity item) {
        return jdbc.query("INSERT INTO source_items(source_namespace, source_item_id, listing_namespace, listing_id, "
                        + "original_url, fetched_at, raw_payload, input_version, normalization_version, input_digest) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?) ON CONFLICT (source_namespace, source_item_id) "
                        + "DO NOTHING RETURNING id",
                ps -> {
                    ps.setString(1, item.sourceNamespace());
                    ps.setString(2, item.sourceItemId());
                    ps.setString(3, item.listingNamespace());
                    ps.setString(4, item.listingId());
                    ps.setString(5, item.originalUrl());
                    ps.setTimestamp(6, java.sql.Timestamp.from(item.fetchedAt()));
                    ps.setString(7, item.rawPayload());
                    ps.setString(8, item.inputVersion());
                    ps.setString(9, item.normalizationVersion());
                    ps.setString(10, item.inputDigest());
                }, rs -> rs.next() ? rs.getLong(1) : null);
    }

    public String digest(String namespace, String itemId) {
        return jdbc.queryForObject("SELECT input_digest FROM source_items WHERE source_namespace = ? AND source_item_id = ?",
                String.class, namespace, itemId);
    }

    public Long insertLiveSnapshot(SourceItemEntity item, Instant updatedAt, String contentDigest) {
        return jdbc.query("INSERT INTO source_items(source_namespace, source_item_id, listing_namespace, listing_id, "
                        + "original_url, fetched_at, raw_payload, input_version, normalization_version, "
                        + "input_digest, source_updated_at, content_digest) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (source_namespace, source_item_id) DO NOTHING RETURNING id",
                ps -> {
                    ps.setString(1, item.sourceNamespace());
                    ps.setString(2, item.sourceItemId());
                    ps.setString(3, item.listingNamespace());
                    ps.setString(4, item.listingId());
                    ps.setString(5, item.originalUrl());
                    ps.setTimestamp(6, Timestamp.from(item.fetchedAt()));
                    ps.setString(7, item.rawPayload());
                    ps.setString(8, item.inputVersion());
                    ps.setString(9, item.normalizationVersion());
                    ps.setString(10, item.inputDigest());
                    ps.setTimestamp(11, updatedAt == null ? null : Timestamp.from(updatedAt));
                    ps.setString(12, contentDigest);
                }, rs -> rs.next() ? rs.getLong(1) : null);
    }

    public String liveDigest(String namespace, String itemId) {
        return jdbc.queryForObject("SELECT content_digest FROM source_items "
                + "WHERE source_namespace = ? AND source_item_id = ?", String.class, namespace, itemId);
    }

    public long insertOrFindObservedSnapshot(SourceItemEntity item, String applyUrl, String contentDigest) {
        Long inserted = jdbc.query("INSERT INTO source_items(source_namespace, source_item_id, listing_namespace, "
                        + "listing_id, original_url, apply_url, fetched_at, raw_payload, input_version, "
                        + "normalization_version, input_digest, content_digest) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?) "
                        + "ON CONFLICT (source_namespace, source_item_id) DO NOTHING RETURNING id",
                ps -> {
                    ps.setString(1, item.sourceNamespace());
                    ps.setString(2, item.sourceItemId());
                    ps.setString(3, item.listingNamespace());
                    ps.setString(4, item.listingId());
                    ps.setString(5, item.originalUrl());
                    ps.setString(6, applyUrl);
                    ps.setTimestamp(7, Timestamp.from(item.fetchedAt()));
                    ps.setString(8, item.rawPayload());
                    ps.setString(9, item.inputVersion());
                    ps.setString(10, item.normalizationVersion());
                    ps.setString(11, item.inputDigest());
                    ps.setString(12, contentDigest);
                }, rs -> rs.next() ? rs.getLong(1) : null);
        if (inserted != null) {
            return inserted;
        }
        return jdbc.queryForObject("SELECT id FROM source_items WHERE source_namespace = ? AND source_item_id = ? "
                        + "AND content_digest = ?", Long.class,
                item.sourceNamespace(), item.sourceItemId(), contentDigest);
    }
}
