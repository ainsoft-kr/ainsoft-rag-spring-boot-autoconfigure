package com.ainsoft.rag.spring

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.mindrot.jbcrypt.BCrypt
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

class RagAdminAccountManagementServiceTest {
    private lateinit var store: InMemoryRagAdminAccountStore
    private lateinit var auditSink: RecordingAuditSink
    private lateinit var service: RagAdminAccountManagementService
    private val actor = RagAdminAccessContext(
        enabled = true,
        authenticated = true,
        username = "admin",
        role = "ADMIN",
        roles = setOf("ADMIN"),
        allowedFeatures = setOf("users")
    )

    @BeforeTest
    fun setUp() {
        store = InMemoryRagAdminAccountStore()
        auditSink = RecordingAuditSink()
        store.upsertUser(
            username = "admin",
            passwordHash = BCrypt.hashpw("secret", BCrypt.gensalt()),
            displayName = "Admin",
            enabled = true,
            roles = setOf("ADMIN")
        )
        service = RagAdminAccountManagementService(
            properties = RagAdminProperties(security = RagAdminSecurityProperties(enabled = true)),
            accountStore = store,
            auditSink = auditSink
        )
    }

    @Test
    fun `create update and delete users`() {
        val created = service.createUser(
            actor,
            RagAdminUserUpsertRequest(
                username = "ops",
                displayName = "Ops User",
                password = "ops-pass",
                enabled = true,
                roles = listOf("OPS", "AUDITOR")
            )
        )
        assertEquals("ops", created.username)
        assertEquals(listOf("AUDITOR", "OPS"), created.roles)
        assertEquals(2, service.listUsers().totalCount)
        assertTrue(BCrypt.checkpw("ops-pass", store.findUser("ops")!!.passwordHash))

        val updated = service.updateUser(
            actor,
            "ops",
            RagAdminUserUpsertRequest(
                displayName = "Operations",
                password = "new-pass",
                enabled = false,
                roles = listOf("OPS")
            )
        )
        assertEquals("Operations", updated.displayName)
        assertEquals(false, updated.enabled)
        assertEquals(listOf("OPS"), updated.roles)
        assertTrue(BCrypt.checkpw("new-pass", store.findUser("ops")!!.passwordHash))

        service.deleteUser(actor, "ops")
        assertEquals(1, service.listUsers().totalCount)
        assertEquals(listOf("CREATE", "UPDATE", "DELETE"), auditSink.actions.map { it.action })
    }

    @Test
    fun `reset password updates hash and records audit`() {
        service.createUser(
            actor,
            RagAdminUserUpsertRequest(
                username = "ops",
                displayName = "Ops User",
                password = "ops-pass",
                enabled = true,
                roles = listOf("OPS")
            )
        )
        val reset = service.resetPassword(actor, "ops", "fresh-pass")
        assertEquals("ops", reset.username)
        assertTrue(BCrypt.checkpw("fresh-pass", store.findUser("ops")!!.passwordHash))
        assertEquals("RESET_PASSWORD", auditSink.actions.last().action)
    }

    @Test
    fun `list users filters and sorts server side`() {
        service.createUser(
            actor,
            RagAdminUserUpsertRequest(
                username = "alpha",
                displayName = "Alpha User",
                password = "alpha-pass",
                enabled = true,
                roles = listOf("OPS")
            )
        )
        service.createUser(
            actor,
            RagAdminUserUpsertRequest(
                username = "zulu",
                displayName = "Zulu User",
                password = "zulu-pass",
                enabled = true,
                roles = listOf("OPS")
            )
        )

        val response = service.listUsers(query = "ops", sort = "displayName", direction = "desc")
        assertEquals(listOf("zulu", "alpha"), response.items.map { it.username })
    }

    @Test
    fun `reject deleting the last enabled admin`() {
        val error = assertFailsWith<ResponseStatusException> {
            service.deleteUser(actor, "admin")
        }
        assertEquals(HttpStatus.CONFLICT, error.statusCode)
    }

    private class RecordingAuditSink : RagAdminUserAuditSink {
        val actions = mutableListOf<ActionRecord>()

        override fun recordUserAudit(
            action: String,
            actorUsername: String?,
            actorRole: String?,
            targetUsername: String,
            success: Boolean,
            message: String?,
            details: Map<String, String>
        ) {
            actions += ActionRecord(
                action = action,
                actorUsername = actorUsername,
                actorRole = actorRole,
                targetUsername = targetUsername,
                success = success,
                message = message
            )
        }
    }

    private data class ActionRecord(
        val action: String,
        val actorUsername: String?,
        val actorRole: String?,
        val targetUsername: String,
        val success: Boolean,
        val message: String?
    )
}
