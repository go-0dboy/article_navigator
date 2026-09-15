package io.github.go0dboy.articlenavigator.storage.database

import androidx.room3.Database
import androidx.room3.RoomDatabase

@Database(
    entities = [
        SourceEntity::class,
        SourceCursorEntity::class,
        SourceCollectionStateEntity::class,
        DiscoveredItemEntity::class,
        RawContentEntity::class,
        DocumentEntity::class,
        DocumentVersionEntity::class,
        DocumentProvenanceEntity::class,
        SeenFingerprintEntity::class,
        InterestEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class ArticleNavigatorDatabase : RoomDatabase() {
    abstract fun sourceDao(): SourceDao
    abstract fun collectionStateDao(): CollectionStateDao
    abstract fun ingestionDao(): IngestionDao
    abstract fun documentDao(): DocumentDao
}
