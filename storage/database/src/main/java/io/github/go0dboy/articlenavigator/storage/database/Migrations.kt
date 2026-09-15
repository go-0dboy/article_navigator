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
    connection.prepare("ALTER TABLE `discovered_items` ADD COLUMN `processingAttempts` INTEGER NOT NULL DEFAULT 0")
        .use { it.step() }
    connection.prepare("ALTER TABLE `discovered_items` ADD COLUMN `nextProcessingAtEpochMillis` INTEGER")
        .use { it.step() }
    connection.prepare("ALTER TABLE `discovered_items` ADD COLUMN `lastProcessingError` TEXT")
        .use { it.step() }
    connection.prepare("CREATE INDEX IF NOT EXISTS `index_discovered_items_nextProcessingAtEpochMillis` ON `discovered_items` (`nextProcessingAtEpochMillis`)")
        .use { it.step() }

    connection.prepare("DELETE FROM `discovered_items` WHERE `sourceId` = 'phase3-sample-rss'")
        .use { it.step() }
    connection.prepare("DELETE FROM `sources` WHERE `id` = 'phase3-sample-rss'")
        .use { it.step() }

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

    connection.prepare("ALTER TABLE `document_provenance` RENAME TO `document_provenance_legacy`")
        .use { it.step() }
    connection.prepare(
        """
        CREATE TABLE `document_provenance` (
            `documentId` TEXT NOT NULL,
            `originKey` TEXT NOT NULL,
            `sourceId` TEXT NOT NULL,
            `discoveredUrl` TEXT NOT NULL,
            `discoveredAtEpochMillis` INTEGER NOT NULL,
            `fetchedAtEpochMillis` INTEGER NOT NULL,
            PRIMARY KEY(`documentId`, `originKey`),
            FOREIGN KEY(`documentId`) REFERENCES `documents`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
            FOREIGN KEY(`sourceId`) REFERENCES `sources`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
    ).use { it.step() }
    connection.prepare(
        """
        INSERT INTO `document_provenance` (
            `documentId`, `originKey`, `sourceId`, `discoveredUrl`, `discoveredAtEpochMillis`, `fetchedAtEpochMillis`
        )
        SELECT
            `documentId`, `sourceId` || '|' || `discoveredUrl`, `sourceId`, `discoveredUrl`, `discoveredAtEpochMillis`, `fetchedAtEpochMillis`
        FROM `document_provenance_legacy`
        """.trimIndent(),
    ).use { it.step() }
    connection.prepare("DROP TABLE `document_provenance_legacy`").use { it.step() }
    connection.prepare("CREATE INDEX IF NOT EXISTS `index_document_provenance_sourceId` ON `document_provenance` (`sourceId`)")
        .use { it.step() }
}

/**
 * v4 hardens collection ownership and long-lived provenance.
 * Network work remains outside DB transactions; only claim/commit state is persisted here.
 */
val MIGRATION_3_4 = Migration(3, 4) { connection ->
    connection.prepare("ALTER TABLE `sources` ADD COLUMN `settingsRevision` INTEGER NOT NULL DEFAULT 0").use { it.step() }
    connection.prepare("ALTER TABLE `sources` ADD COLUMN `leaseToken` TEXT").use { it.step() }
    connection.prepare("ALTER TABLE `sources` ADD COLUMN `leaseExpiresAtEpochMillis` INTEGER").use { it.step() }
    connection.prepare("CREATE INDEX IF NOT EXISTS `index_sources_leaseExpiresAtEpochMillis` ON `sources` (`leaseExpiresAtEpochMillis`)")
        .use { it.step() }

    connection.prepare("ALTER TABLE `discovered_items` ADD COLUMN `resolvedUrl` TEXT").use { it.step() }
    connection.prepare("ALTER TABLE `discovered_items` ADD COLUMN `lastSeenAtEpochMillis` INTEGER NOT NULL DEFAULT 0").use { it.step() }
    connection.prepare("UPDATE `discovered_items` SET `lastSeenAtEpochMillis` = `discoveredAtEpochMillis` WHERE `lastSeenAtEpochMillis` = 0")
        .use { it.step() }
    connection.prepare("CREATE INDEX IF NOT EXISTS `index_discovered_items_lastSeenAtEpochMillis` ON `discovered_items` (`lastSeenAtEpochMillis`)")
        .use { it.step() }

    // Inbox provenance is snapshotted when ingestion attaches the origin. Existing v3 rows are backfilled now.
    connection.prepare("ALTER TABLE `inbox_origins` RENAME TO `inbox_origins_v3`").use { it.step() }
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
    connection.prepare(
        """
        INSERT INTO `inbox_origins` (
            `inboxItemId`, `discoveredItemId`, `sourceId`, `discoveredUrl`, `resolvedUrl`, `canonicalUrl`,
            `discoveredAtEpochMillis`, `fetchedAtEpochMillis`,
            `sourceNameSnapshot`, `sourceUrlSnapshot`, `sourceTypeSnapshot`
        )
        SELECT i.`inboxItemId`, i.`discoveredItemId`, i.`sourceId`, i.`discoveredUrl`, NULL, i.`canonicalUrl`,
               i.`discoveredAtEpochMillis`, i.`fetchedAtEpochMillis`,
               s.`name`, s.`url`, s.`type`
        FROM `inbox_origins_v3` i
        JOIN `sources` s ON s.`id` = i.`sourceId`
        """.trimIndent(),
    ).use { it.step() }
    connection.prepare("DROP TABLE `inbox_origins_v3`").use { it.step() }
    connection.prepare("CREATE UNIQUE INDEX IF NOT EXISTS `index_inbox_origins_discoveredItemId` ON `inbox_origins` (`discoveredItemId`)")
        .use { it.step() }
    connection.prepare("CREATE INDEX IF NOT EXISTS `index_inbox_origins_sourceId` ON `inbox_origins` (`sourceId`)")
        .use { it.step() }

    // Saved provenance is a durable snapshot. Removing a subscription must not delete it.
    connection.prepare("ALTER TABLE `document_provenance` RENAME TO `document_provenance_v3`").use { it.step() }
    connection.prepare(
        """
        CREATE TABLE `document_provenance` (
            `documentId` TEXT NOT NULL,
            `originKey` TEXT NOT NULL,
            `sourceId` TEXT NOT NULL,
            `discoveredUrl` TEXT NOT NULL,
            `resolvedUrl` TEXT,
            `discoveredAtEpochMillis` INTEGER NOT NULL,
            `fetchedAtEpochMillis` INTEGER NOT NULL,
            `sourceNameSnapshot` TEXT NOT NULL,
            `sourceUrlSnapshot` TEXT NOT NULL,
            `sourceTypeSnapshot` TEXT NOT NULL,
            PRIMARY KEY(`documentId`, `originKey`),
            FOREIGN KEY(`documentId`) REFERENCES `documents`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
            FOREIGN KEY(`sourceId`) REFERENCES `sources`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT
        )
        """.trimIndent(),
    ).use { it.step() }
    connection.prepare(
        """
        INSERT INTO `document_provenance` (
            `documentId`, `originKey`, `sourceId`, `discoveredUrl`, `resolvedUrl`,
            `discoveredAtEpochMillis`, `fetchedAtEpochMillis`,
            `sourceNameSnapshot`, `sourceUrlSnapshot`, `sourceTypeSnapshot`
        )
        SELECT p.`documentId`, p.`originKey`, p.`sourceId`, p.`discoveredUrl`, NULL,
               p.`discoveredAtEpochMillis`, p.`fetchedAtEpochMillis`,
               s.`name`, s.`url`, s.`type`
        FROM `document_provenance_v3` p
        JOIN `sources` s ON s.`id` = p.`sourceId`
        """.trimIndent(),
    ).use { it.step() }
    connection.prepare("DROP TABLE `document_provenance_v3`").use { it.step() }
    connection.prepare("CREATE INDEX IF NOT EXISTS `index_document_provenance_sourceId` ON `document_provenance` (`sourceId`)")
        .use { it.step() }

    // Seen history is also retained while a source is archived; an accidental hard delete is rejected.
    connection.prepare("ALTER TABLE `seen_fingerprints` RENAME TO `seen_fingerprints_v3`").use { it.step() }
    connection.prepare(
        """
        CREATE TABLE `seen_fingerprints` (
            `canonicalUrlHash` TEXT NOT NULL,
            `contentHash` TEXT,
            `sourceId` TEXT NOT NULL,
            `seenAtEpochMillis` INTEGER NOT NULL,
            `disposition` TEXT NOT NULL,
            PRIMARY KEY(`canonicalUrlHash`, `sourceId`),
            FOREIGN KEY(`sourceId`) REFERENCES `sources`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT
        )
        """.trimIndent(),
    ).use { it.step() }
    connection.prepare(
        """
        INSERT INTO `seen_fingerprints` (`canonicalUrlHash`, `contentHash`, `sourceId`, `seenAtEpochMillis`, `disposition`)
        SELECT `canonicalUrlHash`, `contentHash`, `sourceId`, `seenAtEpochMillis`, `disposition`
        FROM `seen_fingerprints_v3`
        """.trimIndent(),
    ).use { it.step() }
    connection.prepare("DROP TABLE `seen_fingerprints_v3`").use { it.step() }
    connection.prepare("CREATE INDEX IF NOT EXISTS `index_seen_fingerprints_sourceId` ON `seen_fingerprints` (`sourceId`)")
        .use { it.step() }
}
