package com.example.booklibrary.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val author: String,
    val epubPath: String,
    val coverPath: String?,
    val lastLocator: String? = null,
    val lastReadTime: Long = 0L,
    val sortOrder: Int = 0
)