package com.ainsoft.rag.spring

import com.ainsoft.rag.embeddings.EmbeddingProvider
import java.security.MessageDigest

class HashEmbeddingProvider(
    override val dimensions: Int = 256
) : EmbeddingProvider {
    override fun embed(texts: List<String>): List<FloatArray> {
        return texts.map { text ->
            val hash = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            val vec = FloatArray(dimensions)
            for (i in 0 until dimensions) {
                vec[i] = (hash[i % hash.size].toInt() and 0xFF) / 255.0f
            }
            vec
        }
    }
}
