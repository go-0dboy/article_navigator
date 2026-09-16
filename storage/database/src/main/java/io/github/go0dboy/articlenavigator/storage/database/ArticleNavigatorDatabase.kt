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
        InboxItemEntity::class,
        InboxOriginEntity::class,
        DocumentEntity::class,
        DocumentVersionEntity::class,
        DocumentProvenanceEntity::class,
        SeenFingerprintEntity::class,
        InterestEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class ArticleNavigatorDatabase : RoomDatabase() {
    abstract fun sourceDao(): SourceDao
    abstract fun sourceScheduleDao(): SourceScheduleDao
    abstract fun collectionStateDao(): CollectionStateDao
    abstract fun collectionDao(): CollectionDao
    abstract fun ingestionDao(): IngestionDao
    abstract fun articleProcessingDao(): ArticleProcessingDao
    abstract fun inboxDao(): InboxDao
    abstract fun documentDao(): DocumentDao
}
