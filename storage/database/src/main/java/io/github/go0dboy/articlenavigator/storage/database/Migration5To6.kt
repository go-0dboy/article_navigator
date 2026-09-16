package io.github.go0dboy.articlenavigator.storage.database

import androidx.room3.migration.Migration

/**
 * Persists the parser version with pending Inbox content.
 *
 * v5 Inbox rows were produced before charset-aware extraction shipped, so their durable text is
 * conservatively labelled as default extractor v1. New runtime rows explicitly persist their
 * parser version through InboxItemEntity and Save copies that value into DocumentVersion.
 */
val MIGRATION_5_6 = Migration(5, 6) { connection ->
    connection.prepare(
        "ALTER TABLE `inbox_items` ADD COLUMN `parserVersion` TEXT NOT NULL " +
            "DEFAULT 'default-content-extractor-v1'",
    ).use { it.step() }
}
