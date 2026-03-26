package com.ainsoft.rag.graph

import com.ainsoft.rag.api.UpsertDocumentRequest
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.net.URI
import java.util.Locale

data class GraphRagBlock(
    val index: Int,
    val sectionLabel: String,
    val text: String,
    val preview: String,
    val offsetStart: Int,
    val offsetEnd: Int
)

data class GraphRagEntityDraft(
    val normalizedName: String,
    val displayName: String,
    val type: String = "TERM"
)

data class GraphRagRelationDraft(
    val fromEntity: String,
    val toEntity: String,
    val relationType: String,
    val source: String = "cooccurrence"
)

data class GraphRagExtraction(
    val blocks: List<GraphRagBlock> = emptyList(),
    val entitiesByBlock: Map<Int, List<GraphRagEntityDraft>> = emptyMap(),
    val relations: List<GraphRagRelationDraft> = emptyList(),
    val sourceUris: List<String> = emptyList()
)

enum class GraphRagExtractionPreset(
    val description: String,
    val extraRules: String
) {
    DEFAULT(
        description = "Balanced technical extraction",
        extraRules = """
            - Default to balanced extraction for mixed documentation.
            - Keep technical names, config keys, endpoints, and schema labels.
        """.trimIndent()
    ),
    TECHNICAL(
        description = "Technical docs and code",
        extraRules = """
            - Prioritize product names, APIs, classes, methods, config keys, endpoint paths, and schema labels.
            - Treat code symbols, package names, and model names as high-value entities.
        """.trimIndent()
    ),
    KOREAN_DOC(
        description = "Korean business docs",
        extraRules = """
            - Prefer visible Korean nouns, section titles, and explicit organization names.
            - Avoid over-inferring latent entities from generic prose.
        """.trimIndent()
    ),
    POLICY(
        description = "Policy / regulation docs",
        extraRules = """
            - Prioritize sections, clause numbers, article references, exceptions, roles, and obligations.
            - Extract principal names and access-control terms when present.
        """.trimIndent()
    ),
    WEBSITE(
        description = "Website / crawl content",
        extraRules = """
            - Prioritize page titles, headings, navigation labels, URLs, product names, and external references.
            - Prefer entity mentions repeated across nearby blocks.
        """.trimIndent()
    );

    fun defaultModelForKind(kind: String): String? =
        when (kind.trim().lowercase(Locale.ROOT)) {
            "openai", "openai-compatible" -> when (this) {
                TECHNICAL -> "gpt-4o"
                DEFAULT, KOREAN_DOC, POLICY, WEBSITE -> "gpt-4o-mini"
            }

            "anthropic", "claude" -> when (this) {
                TECHNICAL -> "claude-3-5-sonnet-latest"
                DEFAULT, KOREAN_DOC, POLICY, WEBSITE -> "claude-3-5-sonnet-latest"
            }

            "gemini", "google-gemini", "vertex", "vertex-ai", "vertex-gemini" -> when (this) {
                TECHNICAL -> "gemini-2.0-pro"
                DEFAULT, KOREAN_DOC, POLICY, WEBSITE -> "gemini-2.0-flash"
            }

            else -> null
        }

    companion object {
        fun fromName(value: String?): GraphRagExtractionPreset =
            entries.firstOrNull {
                it.name.equals(value?.trim().orEmpty(), ignoreCase = true)
            } ?: DEFAULT
    }
}

interface GraphRagExtractionStrategy {
    fun extract(request: UpsertDocumentRequest, previewChars: Int = 240): GraphRagExtraction
    fun withPreset(preset: GraphRagExtractionPreset): GraphRagExtractionStrategy = this
}

class GraphRagExtractionService : GraphRagExtractionStrategy {
    private fun extractHeuristic(request: UpsertDocumentRequest, previewChars: Int = 240): GraphRagExtraction {
        val normalizedText = request.normalizedText.trim()
        if (normalizedText.isBlank()) {
            return GraphRagExtraction(sourceUris = request.sourceUri?.let(::listOf).orEmpty())
        }

        val blocks = splitBlocks(normalizedText, previewChars)
        val entitiesByBlock = blocks.associate { block ->
            block.index to extractEntities(block.text)
        }
        val relations = buildRelations(entitiesByBlock)
        val sourceUris = extractSourceUris(normalizedText) + request.sourceUri.orEmpty().takeIf { it.isNotBlank() }.let(::listOfNotNull)
        return GraphRagExtraction(
            blocks = blocks,
            entitiesByBlock = entitiesByBlock,
            relations = relations,
            sourceUris = sourceUris.distinct()
        )
    }

    override fun extract(request: UpsertDocumentRequest, previewChars: Int): GraphRagExtraction =
        extractHeuristic(request, previewChars)

    override fun withPreset(preset: GraphRagExtractionPreset): GraphRagExtractionStrategy =
        when (preset) {
            GraphRagExtractionPreset.DEFAULT -> this
            else -> PresetGraphRagExtractionService(this, preset)
        }

    private fun splitBlocks(text: String, previewChars: Int): List<GraphRagBlock> {
        val lines = text.lineSequence().toList()
        val blocks = ArrayList<GraphRagBlock>()
        var current = ArrayList<String>()
        var currentStart = -1
        var offset = 0

        fun flush() {
            if (current.isEmpty()) return
            val blockText = current.joinToString(" ").replace(Regex("\\s+"), " ").trim()
            if (blockText.isBlank()) {
                current.clear()
                currentStart = -1
                return
            }
            val blockOffsetStart = if (currentStart >= 0) currentStart else 0
            val blockOffsetEnd = (blockOffsetStart + blockText.length).coerceAtLeast(blockOffsetStart)
            blocks += GraphRagBlock(
                index = blocks.size,
                sectionLabel = current.firstOrNull()?.takeSectionLabel() ?: "content",
                text = blockText,
                preview = if (blockText.length <= previewChars) blockText else blockText.take(previewChars - 3).trimEnd() + "...",
                offsetStart = blockOffsetStart,
                offsetEnd = blockOffsetEnd
            )
            current.clear()
            currentStart = -1
        }

        lines.forEach { line ->
            val trimmed = line.trimEnd()
            val isHeading = trimmed.isSectionHeading()
            if (isHeading) {
                flush()
                current += trimmed
                currentStart = offset
            } else if (trimmed.isBlank()) {
                flush()
            } else {
                if (current.isEmpty()) currentStart = offset
                current += trimmed
            }
            offset += line.length + 1
        }
        flush()
        return if (blocks.isNotEmpty()) blocks else listOf(
            GraphRagBlock(
                index = 0,
                sectionLabel = "content",
                text = text.replace(Regex("\\s+"), " ").trim(),
                preview = text.replace(Regex("\\s+"), " ").trim().let { collapsed ->
                    if (collapsed.length <= previewChars) collapsed else collapsed.take(previewChars - 3).trimEnd() + "..."
                },
                offsetStart = 0,
                offsetEnd = text.length
            )
        )
    }

    private fun extractEntities(text: String): List<GraphRagEntityDraft> {
        val normalized = text.replace(Regex("\\s+"), " ").trim()
        if (normalized.isBlank()) return emptyList()

        val stopWords = setOf(
            "the", "and", "for", "with", "that", "this", "from", "into", "about", "your", "are", "was", "were",
            "html", "body", "head", "title", "meta", "http", "https", "www", "com", "org", "net",
            "document", "section", "chunk", "tenant", "graph", "rag", "admin"
        )

        return Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}._-]{2,}")
            .findAll(normalized.take(5_000))
            .map { it.value.trim().trimEnd('.', ',', ';', ':', ')', ']', '}', '"', '\'') }
            .filter { it.length >= 3 }
            .filterNot { it.lowercase(Locale.ROOT) in stopWords }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .take(24)
            .map { token ->
                GraphRagEntityDraft(
                    normalizedName = token.lowercase(Locale.ROOT),
                    displayName = token,
                    type = inferType(token)
                )
            }
            .toList()
    }

    private fun inferType(token: String): String {
        val trimmed = token.trim()
        return when {
            trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true) -> "URL"
            trimmed.any { it.isUpperCase() } && trimmed.length >= 2 -> "ACRONYM"
            trimmed.any { it in listOf('.', '_', '-') } -> "TERM"
            trimmed.any { it.code > 0x7F } -> "TERM"
            else -> "TERM"
        }
    }

    private fun buildRelations(entitiesByBlock: Map<Int, List<GraphRagEntityDraft>>): List<GraphRagRelationDraft> {
        val relations = linkedMapOf<String, GraphRagRelationDraft>()
        entitiesByBlock.values.forEach { entities ->
            entities.zipWithNext().forEach { (left, right) ->
                if (left.normalizedName == right.normalizedName) return@forEach
                val relation = GraphRagRelationDraft(
                    fromEntity = left.normalizedName,
                    toEntity = right.normalizedName,
                    relationType = GraphSchema.Relations.RELATED_TO
                )
                relations[relationKey(relation)] = relation
            }
        }
        return relations.values.toList()
    }

    private fun extractSourceUris(text: String): List<String> =
        Regex("""https?://[^\s<>()"']+""")
            .findAll(text)
            .map { it.value.trimEnd('.', ',', ';', ':', ')', ']', '}', '"', '\'') }
            .distinct()
            .take(8)
            .toList()

    private fun relationKey(relation: GraphRagRelationDraft): String =
        "${relation.fromEntity}->${relation.toEntity}#${relation.relationType}"

    private fun String.isSectionHeading(): Boolean {
        val normalized = trim()
        return normalized.startsWith("#") ||
            normalized.matches(Regex("^(\\d+[.)]|[가-힣]{2,}\\s*[:：]).+")) ||
            normalized.matches(Regex("^제\\d+조.*"))
    }

    private fun String.takeSectionLabel(): String =
        trim().removePrefix("#").trim().take(80).ifBlank { "content" }
}

internal class PresetGraphRagExtractionService(
    private val delegate: GraphRagExtractionStrategy,
    private val preset: GraphRagExtractionPreset
) : GraphRagExtractionStrategy {
    override fun extract(request: UpsertDocumentRequest, previewChars: Int): GraphRagExtraction =
        delegate.extract(
            request.copy(
                metadata = request.metadata + mapOf(
                    "rag.graphExtractionPreset" to preset.name.lowercase(Locale.ROOT)
                )
            ),
            previewChars
        )

    override fun withPreset(preset: GraphRagExtractionPreset): GraphRagExtractionStrategy =
        delegate.withPreset(preset)
}

internal class LlmGraphRagExtractionService(
    private val provider: com.ainsoft.rag.api.TextGenerationProvider,
    private val preset: GraphRagExtractionPreset = GraphRagExtractionPreset.DEFAULT
) : GraphRagExtractionStrategy {
    private val heuristic = GraphRagExtractionService()
    private val mapper = jacksonObjectMapper()

    override fun extract(request: UpsertDocumentRequest, previewChars: Int): GraphRagExtraction {
        val base = heuristic.extract(request, previewChars)
        if (request.normalizedText.isBlank()) {
            return base
        }

        val blockDrafts = base.blocks.associateWith { block ->
            extractBlockDraft(
                request = request,
                block = block,
                previewChars = previewChars
            )
        }

        val entitiesByBlock = blockDrafts.mapValues { (block, draft) ->
            draft.entities.ifEmpty {
                heuristic.extract(request.copy(normalizedText = block.text), previewChars)
                    .entitiesByBlock[0].orEmpty()
            }
        }.mapKeys { (block, _) -> block.index }
        val relations = buildRelations(
            base = base,
            entitiesByBlock = entitiesByBlock,
            llmRelations = blockDrafts.values.flatMap { it.relations }
        )
        return base.copy(
            entitiesByBlock = entitiesByBlock,
            relations = relations,
            sourceUris = (base.sourceUris + blockDrafts.values.flatMap { it.sourceUris }).distinct()
        )
    }

    private data class BlockDraft(
        val entities: List<GraphRagEntityDraft> = emptyList(),
        val relations: List<GraphRagRelationDraft> = emptyList(),
        val sourceUris: List<String> = emptyList()
    )

    private fun extractBlockDraft(
        request: UpsertDocumentRequest,
        block: GraphRagBlock,
        previewChars: Int
    ): BlockDraft {
        val systemPrompt = """
            You extract graph facts for retrieval indexing.
            Return only valid JSON and no markdown fences.
            JSON shape:
            {
              "entities": [
                {"normalizedName":"string","displayName":"string","type":"TERM"}
              ],
              "relations": [
                {"fromEntity":"string","toEntity":"string","relationType":"RELATED_TO","source":"cooccurrence"}
              ],
              "sourceUris": ["https://..."]
            }
            Rules:
            - The input may mix English and Korean.
            - Keep displayName faithful to the source text.
            - normalizedName should be lowercase and stable; use ASCII when possible, but keep Korean if needed.
            - Prefer concrete entities named in the text: OpenAI, FalkorDB, Lucene, Spring, Kotlin, APIs, companies, documents, services, people, tables, URLs, file names, features, tenant names, roles, and domain nouns.
            - For technical documents, prefer product names, class names, package names, config keys, method names, model names, endpoint paths, and schema labels.
            - For Korean documents, prefer visible nouns and section titles rather than inferring hidden concepts.
            - Do not invent entities that are not present in the block.
            - Prefer 3 to 12 entities per block.
            - relationType should be one of RELATED_TO, CITES, MENTIONS.
            - Use RELATED_TO for co-occurrence, MENTIONS for mentions in text, and CITES for URLs.
            - If nothing is found, return empty arrays.
            - If you are uncertain, omit the item rather than guessing.
            ${preset.extraRules}
        """.trimIndent()

        val userPrompt = buildString {
            appendLine("tenantId=${request.tenantId}")
            appendLine("docId=${request.docId}")
            appendLine("blockIndex=${block.index}")
            appendLine("sectionLabel=${block.sectionLabel}")
            appendLine("previewChars=$previewChars")
            request.sourceUri?.takeIf { it.isNotBlank() }?.let { appendLine("sourceUri=$it") }
            appendLine()
            appendLine(block.text.take(5000))
        }
        val completion = runCatching { provider.complete(systemPrompt, userPrompt) }.getOrDefault("")
        val root = runCatching { mapper.readTree(stripCodeFence(completion)) }.getOrNull() ?: return BlockDraft()
        val entities = root.path("entities").takeIf { it.isArray }?.mapNotNull { node ->
            val normalizedName = node.path("normalizedName").asText().trim()
            val displayName = node.path("displayName").asText().trim()
            if (normalizedName.isBlank() || displayName.isBlank()) {
                null
            } else {
                GraphRagEntityDraft(
                    normalizedName = normalizedName.lowercase(Locale.ROOT),
                    displayName = displayName,
                    type = node.path("type").asText("TERM")
                )
            }
        }.orEmpty()
        val relations = root.path("relations").takeIf { it.isArray }?.mapNotNull { node ->
            val fromEntity = node.path("fromEntity").asText().trim().lowercase(Locale.ROOT)
            val toEntity = node.path("toEntity").asText().trim().lowercase(Locale.ROOT)
            val relationType = node.path("relationType").asText().trim().uppercase(Locale.ROOT)
            if (fromEntity.isBlank() || toEntity.isBlank()) {
                null
            } else {
                GraphRagRelationDraft(
                    fromEntity = fromEntity,
                    toEntity = toEntity,
                    relationType = when (relationType) {
                        "RELATED_TO", "MENTIONS", "CITES" -> relationType
                        else -> GraphSchema.Relations.RELATED_TO
                    },
                    source = node.path("source").asText("llm")
                )
            }
        }.orEmpty()
        val sourceUris = root.path("sourceUris").takeIf { it.isArray }?.mapNotNull { node ->
            val value = node.asText().trim()
            value.takeIf { it.isNotBlank() }
        }.orEmpty()
        return BlockDraft(
            entities = entities,
            relations = relations,
            sourceUris = sourceUris
        )
    }

    private fun buildRelations(
        base: GraphRagExtraction,
        entitiesByBlock: Map<Int, List<GraphRagEntityDraft>>,
        llmRelations: List<GraphRagRelationDraft>
    ): List<GraphRagRelationDraft> {
        val relations = linkedMapOf<String, GraphRagRelationDraft>()
        base.relations.forEach { relations[relationKey(it)] = it }
        llmRelations.forEach { relations[relationKey(it)] = it }
        entitiesByBlock.values.forEach { entities ->
            entities.zipWithNext().forEach { (left, right) ->
                if (left.normalizedName != right.normalizedName) {
                    val relation = GraphRagRelationDraft(
                        fromEntity = left.normalizedName,
                        toEntity = right.normalizedName,
                        relationType = GraphSchema.Relations.RELATED_TO,
                        source = "llm_cooccurrence"
                    )
                    relations[relationKey(relation)] = relation
                }
            }
        }
        return relations.values.toList()
    }

    private fun relationKey(relation: GraphRagRelationDraft): String =
        "${relation.fromEntity}->${relation.toEntity}#${relation.relationType}"

    private fun stripCodeFence(text: String): String {
        val trimmed = text.trim()
        return if (trimmed.startsWith("```")) {
            trimmed
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
        } else trimmed
    }
}
