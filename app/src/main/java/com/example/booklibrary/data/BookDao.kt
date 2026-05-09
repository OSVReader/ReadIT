package com.example.booklibrary.data

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface BookDao {

    @Insert
    suspend fun insert(book: BookEntity): Long

    @Query("SELECT * FROM books ORDER BY sortOrder DESC")
    fun getAll(): LiveData<List<BookEntity>>

    @Query("SELECT COALESCE(MAX(sortOrder), 0) FROM books")
    suspend fun getMaxSortOrder(): Int

    @Query("UPDATE books SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun updateSortOrder(id: Int, sortOrder: Int)

    @Query("SELECT * FROM books WHERE id = :id LIMIT 1")
    suspend fun getById(id: Int): BookEntity?

    @Query("SELECT * FROM books WHERE epubPath = :epubPath LIMIT 1")
    suspend fun getByPath(epubPath: String): BookEntity?

    @Query("UPDATE books SET title = :title, author = :author, coverPath = :coverPath WHERE id = :id")
    suspend fun updateMetadata(id: Int, title: String, author: String, coverPath: String?)

    @Query("UPDATE books SET lastLocator = :locatorBase64, lastReadTime = :time WHERE id = :id")
    suspend fun updateLastLocator(id: Int, locatorBase64: String?, time: Long)

    @Query("SELECT lastLocator FROM books WHERE id = :id LIMIT 1")
    suspend fun getLastLocator(id: Int): String?

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun deleteById(id: Int)
}
