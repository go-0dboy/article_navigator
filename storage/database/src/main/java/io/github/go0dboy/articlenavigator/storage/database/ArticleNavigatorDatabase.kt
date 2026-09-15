package io.github.go0dboy.articlenavigator.storage.database

import androidx.room3.Database
import androidx.room3.RoomDatabase

@Database(
    entities = [
        SourceEntity::class,
        SourceCursorEntity::class,
        DocumentEntity::class,
        DocumentVersionEntity::class,
        SeenFingerprintEntity::class,
        InterestEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class ArticleNavigatorDatabase : RoomDatabase() {
    abstract fun sourceDao(): SourceDao
    abstract fun documentDao(): DocumentDao
}
