package com.example.booklibrary.reader

object HrefNormalizer {
    fun normalize(href: Any?): String? {
        return href
            ?.toString()
            ?.trim()
            ?.replace('\\', '/')
            ?.substringBefore('#')
            ?.substringBefore('?')
            ?.trimStart('/')
            ?.takeIf { it.isNotBlank() }
    }

    fun equivalent(left: Any?, right: Any?): Boolean {
        val normalizedLeft = normalize(left)
        val normalizedRight = normalize(right)
        return normalizedLeft != null && normalizedLeft == normalizedRight
    }
}
