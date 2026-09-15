package io.github.go0dboy.articlenavigator.storage.database

import androidx.room3.migration.Migration

val MIGRATION_1_2 = Migration(1, 2) { connection ->
    connection.prepare(
        """
        CREATE TABLE IF NOT EXISTS `source_collection_states` (
            `sourceId` TEXT NOT NULL,
            `consecutiveFailures` INTEGER NOT NULL,
            `lastAttemptAtEpochMillis` INTEGER,
            `lastErrorType` TEXT,
            `lastErrorMessage` TEXT,
            `lastDiscoveredCount` INTEGER NOT NULL,
            PRIMARY KEY(`sourceId`),
            FOREIGN KEY(`sourceId`) REFERENCES `sources`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
    ).use { statement -> statement.step() }
}

val MIGRATION_2_3 = Migration(2, 3) { connection ->
    connection.prepare(
        """
        CREATE TABLE IF NOT EXISTS `inbox_items` (
            `id` TEXT NOT NULL,
            `canonicalUrl` TEXT NOT NULL,
            `title` TEXT NOT NULL,
            `publishedAtEpochMillis` INTEGER,
            `normalizedText` TEXT NOT NULL,
            `contentHash` TEXT NOT NULL,
            `createdAtEpochMillis` INTEGER NOT NULL,
            `updatedAtEpochMillis` INTEGER NOT NULL,
            PRIMARY KEY(`id`)
        )
        """.trimIndent(),
    ).use { it.step() }

    connection.prepare("CREATE UNIQUE INDEX IF NOT EXISTS `index_inbox_items_canonicalUrl` ON `inbox_items` (`canonicalUrl`)")
        .use { it.step() }
    connection.prepare("CREATE INDEX IF NOT EXISTS `index_inbox_items_contentHash` ON `inbox_items` (`contentHash`)")
        .use { it.step() }
    connection.prepare("CREATE INDEX IF NOT EXISTS `index_inbox_items_createdAtEpochMillis` ON `inbox_items` (`createdAtEpochMillis`)")
        .use { it.step() }

    connection.prepare(
        """
        CREATE TABLE IF NOT EXISTS `inbox_origins` (
            `inboxItemId` TEXT NOT NULL,
            `discoveredItemId` TEXT NOT NULL,
            `sourceId` TEXT NOT NULL,
            `discoveredUrl` TEXT NOT NULL,
            `canonicalUrl` TEXT NOT NULL,
            `discoveredAtEpochMillis` INTEGER NOT NULL,
            `fetchedAtEpochMillis` INTEGER NOT NULL,
            PRIMARY KEY(`inboxItemId`, `discoveredItemId`),
            FOREIGN KEY(`inboxItemId`) REFERENCES `inbox_items`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
            FOREIGN KEY(`sourceId`) REFERENCES `sources`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
    ).use { it.step() }

    connection.prepare("CREATE UNIQUE INDEX IF NOT EXISTS `index_inbox_origins_discoveredItemId` ON `inbox_origins` (`discoveredItemId`)")
        .use { it.step() }
    connection.prepare("CREATE INDEX IF NOT EXISTS `index_inbox_origins_sourceId` ON `inbox_origins` (`sourceId`)")
        .use { it.step() }
    connection.prepare("CREATE INDEX IF NOT EXISTS `index_documents_contentHash` ON `documents` (`contentHash`)")
        .use { it.step() }
}
