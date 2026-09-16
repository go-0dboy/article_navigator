package io.github.go0dboy.articlenavigator.storage.database

import androidx.room3.migration.Migration

/**
 * Adds nullable structured-content columns without rewriting historical text or provenance.
 * Existing v1-v6 rows remain valid text-only records and are rendered through compatibility mode.
 */
val MIGRATION_6_7 = Migration(6, 7) { connection ->
    connection.prepare("ALTER TABLE `inbox_items` ADD COLUMN `structuredContentFormat` TEXT").use { it.step() }
    connection.prepare("ALTER TABLE `inbox_items` ADD COLUMN `structuredContent` TEXT").use { it.step() }
    connection.prepare("ALTER TABLE `documents` ADD COLUMN `structuredContentFormat` TEXT").use { it.step() }
    connection.prepare("ALTER TABLE `documents` ADD COLUMN `structuredContent` TEXT").use { it.step() }
    connection.prepare("ALTER TABLE `document_versions` ADD COLUMN `structuredContentFormat` TEXT").use { it.step() }
    connection.prepare("ALTER TABLE `document_versions` ADD COLUMN `structuredContent` TEXT").use { it.step() }
}
