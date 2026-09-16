package io.github.go0dboy.articlenavigator.storage.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection

/**
 * v5 adds persisted article-processing ownership and crash-resumable raw HTTP responses.
 *
 * Two different v4 layouts existed in repository history. The earlier one did not yet contain
 * Inbox source snapshots and used a CASCADE Source FK; the later one contained snapshots and used
 * RESTRICT. This migration intentionally detects the physical v4 layout and accepts both without
 * destructive fallback.
 */
val MIGRATION_4_5 = Migration(4, 5) { connection ->
    connection.prepare("ALTER TABLE `discovered_items` ADD COLUMN `processingLeaseToken` TEXT").use { it.step() }
    connection.prepare("ALTER TABLE `discovered_items` ADD COLUMN `processingLeaseExpiresAtEpochMillis` INTEGER").use { it.step() }
    connection.prepare(
        "CREATE INDEX IF NOT EXISTS `index_discovered_items_processingLeaseExpiresAtEpochMillis` " +
            "ON `discovered_items` (`processingLeaseExpiresAtEpochMillis`)",
    ).use { it.step() }

    migrateInboxOriginsV4ToV5(connection)
    migrateRawContentsV4ToV5(connection)
}

private fun migrateInboxOriginsV4ToV5(connection: SQLiteConnection) {
    val hasSnapshots = hasColumn(connection, "inbox_origins", "sourceNameSnapshot") &&
        hasColumn(connection, "inbox_origins", "sourceUrlSnapshot") &&
        hasColumn(connection, "inbox_origins", "sourceTypeSnapshot")

    connection.prepare("ALTER TABLE `inbox_origins` RENAME TO `inbox_origins_v4`").use { it.step() }
    connection.prepare(
        """
        CREATE TABLE `inbox_origins` (
            `inboxItemId` TEXT NOT NULL,
            `discoveredItemId` TEXT NOT NULL,
            `sourceId` TEXT NOT NULL,
            `discoveredUrl` TEXT NOT NULL,
            `resolvedUrl` TEXT,
            `canonicalUrl` TEXT NOT NULL,
            `discoveredAtEpochMillis` INTEGER NOT NULL,
            `fetchedAtEpochMillis` INTEGER NOT NULL,
            `sourceNameSnapshot` TEXT NOT NULL,
            `sourceUrlSnapshot` TEXT NOT NULL,
            `sourceTypeSnapshot` TEXT NOT NULL,
            PRIMARY KEY(`inboxItemId`, `discoveredItemId`),
            FOREIGN KEY(`inboxItemId`) REFERENCES `inbox_items`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
            FOREIGN KEY(`sourceId`) REFERENCES `sources`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT
        )
        """.trimIndent(),
    ).use { it.step() }

    val copySql = if (hasSnapshots) {
        """
        INSERT INTO `inbox_origins` (
            `inboxItemId`, `discoveredItemId`, `sourceId`, `discoveredUrl`, `resolvedUrl`, `canonicalUrl`,
            `discoveredAtEpochMillis`, `fetchedAtEpochMillis`,
            `sourceNameSnapshot`, `sourceUrlSnapshot`, `sourceTypeSnapshot`
        )
        SELECT `inboxItemId`, `discoveredItemId`, `sourceId`, `discoveredUrl`, `resolvedUrl`, `canonicalUrl`,
               `discoveredAtEpochMillis`, `fetchedAtEpochMillis`,
               `sourceNameSnapshot`, `sourceUrlSnapshot`, `sourceTypeSnapshot`
        FROM `inbox_origins_v4`
        """.trimIndent()
    } else {
        """
        INSERT INTO `inbox_origins` (
            `inboxItemId`, `discoveredItemId`, `sourceId`, `discoveredUrl`, `resolvedUrl`, `canonicalUrl`,
            `discoveredAtEpochMillis`, `fetchedAtEpochMillis`,
            `sourceNameSnapshot`, `sourceUrlSnapshot`, `sourceTypeSnapshot`
        )
        SELECT i.`inboxItemId`, i.`discoveredItemId`, i.`sourceId`, i.`discoveredUrl`, i.`resolvedUrl`, i.`canonicalUrl`,
               i.`discoveredAtEpochMillis`, i.`fetchedAtEpochMillis`,
               s.`name`, s.`url`, s.`type`
        FROM `inbox_origins_v4` i
        JOIN `sources` s ON s.`id` = i.`sourceId`
        """.trimIndent()
    }
    connection.prepare(copySql).use { it.step() }
    connection.prepare("DROP TABLE `inbox_origins_v4`").use { it.step() }
    connection.prepare(
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_inbox_origins_discoveredItemId` ON `inbox_origins` (`discoveredItemId`)",
    ).use { it.step() }
    connection.prepare("CREATE INDEX IF NOT EXISTS `index_inbox_origins_sourceId` ON `inbox_origins` (`sourceId`)")
        .use { it.step() }
}

private fun migrateRawContentsV4ToV5(connection: SQLiteConnection) {
    connection.prepare("ALTER TABLE `raw_contents` RENAME TO `raw_contents_v4`").use { it.step() }
    connection.prepare(
        """
        CREATE TABLE `raw_contents` (
            `discoveredItemId` TEXT NOT NULL,
            `contentType` TEXT,
            `payload` BLOB NOT NULL,
            `resolvedUrl` TEXT,
            `fetchedAtEpochMillis` INTEGER NOT NULL,
            `httpStatus` INTEGER NOT NULL,
            `expiresAtEpochMillis` INTEGER,
            PRIMARY KEY(`discoveredItemId`),
            FOREIGN KEY(`discoveredItemId`) REFERENCES `discovered_items`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
    ).use { it.step() }
    connection.prepare(
        """
        INSERT INTO `raw_contents` (
            `discoveredItemId`, `contentType`, `payload`, `resolvedUrl`,
            `fetchedAtEpochMillis`, `httpStatus`, `expiresAtEpochMillis`
        )
        SELECT r.`discoveredItemId`, r.`mimeType`, CAST(r.`payload` AS BLOB),
               COALESCE(d.`resolvedUrl`, d.`canonicalUrl`, d.`url`),
               r.`fetchedAtEpochMillis`, r.`httpStatus`, r.`expiresAtEpochMillis`
        FROM `raw_contents_v4` r
        JOIN `discovered_items` d ON d.`id` = r.`discoveredItemId`
        """.trimIndent(),
    ).use { it.step() }
    connection.prepare("DROP TABLE `raw_contents_v4`").use { it.step() }
}

private fun hasColumn(connection: SQLiteConnection, table: String, column: String): Boolean {
    var found = false
    connection.prepare("PRAGMA table_info(`$table`)").use { statement ->
        while (statement.step()) {
            if (statement.getText(1) == column) {
                found = true
                break
            }
        }
    }
    return found
}
