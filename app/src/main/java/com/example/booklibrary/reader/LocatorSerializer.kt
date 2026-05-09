package com.example.booklibrary.reader

import android.os.Build
import android.os.Parcel
import android.util.Base64
import com.example.booklibrary.util.SafeLog
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator

object LocatorSerializer {

    private const val TAG = "LocatorSerializer"

    fun toBase64(locator: Locator): String {
        val json = locator.toJSON().toString()
        return Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    fun fromBase64(base64: String): Locator? {
        // Try JSON first (new format)
        val jsonLocator = tryFromJsonBase64(base64)
        if (jsonLocator != null) return jsonLocator

        // Fall back to Parcelable (legacy format)
        return tryFromParcelableBase64(base64)
    }

    private fun tryFromJsonBase64(base64: String): Locator? {
        return try {
            val bytes = Base64.decode(base64, Base64.NO_WRAP)
            val json = String(bytes, Charsets.UTF_8)
            val obj = JSONObject(json)
            Locator.fromJSON(obj)
        } catch (e: Exception) {
            null
        }
    }

    private fun tryFromParcelableBase64(base64: String): Locator? {
        return try {
            val bytes = Base64.decode(base64, Base64.NO_WRAP)
            val parcel = Parcel.obtain()
            try {
                parcel.unmarshall(bytes, 0, bytes.size)
                parcel.setDataPosition(0)
                val loader = Locator::class.java.classLoader
                val locator = if (Build.VERSION.SDK_INT >= 33) {
                    parcel.readParcelable(loader, Locator::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    parcel.readParcelable<Locator>(loader)
                }
                if (locator != null) {
                    SafeLog.d(TAG, "Migrated legacy Parcelable locator to JSON format")
                }
                locator
            } finally {
                parcel.recycle()
            }
        } catch (e: Exception) {
            SafeLog.w(TAG, "Failed to deserialize locator", e)
            null
        }
    }
}
