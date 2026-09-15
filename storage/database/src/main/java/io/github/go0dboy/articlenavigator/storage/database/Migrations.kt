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
    ).use { statement ->
        statement.step()
    }
}
