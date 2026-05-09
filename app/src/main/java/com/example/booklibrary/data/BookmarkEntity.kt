package com.example.booklibrary.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val bookId: Int,
    val locatorBase64: String,
    val title: String?,
    val textSnippet: String?,
    val progression: Double,
    val createdAt: Long = System.currentTimeMillis()
)
