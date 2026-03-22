package com.ainsoft.rag.spring

import java.util.concurrent.ConcurrentHashMap

data class RagAdminStoredUser(
    val username: String,
    val passwordHash: String,
    val displayName: String?,
    val enabled: Boolean,
    val roles: Set<String>
)

interface RagAdminAccountStore {
    fun findUser(username: String): RagAdminStoredUser?
    fun upsertUser(
        username: String,
        passwordHash: String,
        displayName: String?,
        enabled: Boolean,
        roles: Set<String>
    )
    fun deleteUser(username: String): Boolean
    fun allUsers(): List<RagAdminStoredUser>
}

class InMemoryRagAdminAccountStore : RagAdminAccountStore {
    private val users = ConcurrentHashMap<String, RagAdminStoredUser>()

    override fun findUser(username: String): RagAdminStoredUser? = users[username]

    override fun upsertUser(
        username: String,
        passwordHash: String,
        displayName: String?,
        enabled: Boolean,
        roles: Set<String>
    ) {
        users[username] = RagAdminStoredUser(
            username = username,
            passwordHash = passwordHash,
            displayName = displayName,
            enabled = enabled,
            roles = roles
        )
    }

    override fun deleteUser(username: String): Boolean = users.remove(username) != null

    override fun allUsers(): List<RagAdminStoredUser> = users.values.sortedBy { it.username }
}
