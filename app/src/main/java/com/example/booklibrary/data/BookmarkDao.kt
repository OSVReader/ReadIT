package com.example.booklibrary.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface BookmarkDao {

    @Insert
    suspend fun insert(bookmark: BookmarkEntity): Long

    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY progression ASC")
    suspend fun getByBook(bookId: Int): List<BookmarkEntity>

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteById(id: Int)

    @Query("DELETE FROM bookmarks WHERE bookId = :bookId")
    suspend fun deleteByBook(bookId: Int)
}
