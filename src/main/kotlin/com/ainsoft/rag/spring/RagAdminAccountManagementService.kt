package com.ainsoft.rag.spring

import org.mindrot.jbcrypt.BCrypt
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

class RagAdminAccountManagementService(
    private val properties: RagAdminProperties,
    private val accountStore: RagAdminAccountStore,
    private val auditSink: RagAdminUserAuditSink
) {
    fun listUsers(
        query: String? = null,
        sort: String? = null,
        direction: String? = null
    ): RagAdminUsersResponse {
        val normalizedQuery = query?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        val users = accountStore.allUsers()
            .asSequence()
            .filter { user ->
                normalizedQuery == null || matchesQuery(user, normalizedQuery)
            }
            .sortedWith(userComparator(sort, direction))
            .map { it.toResponse() }
            .toList()
        return RagAdminUsersResponse(
            items = users,
            totalCount = users.size,
            enabledCount = users.count { it.enabled },
            adminCount = users.count { it.enabled && it.isAdmin }
        )
    }

    fun createUser(actor: RagAdminAccessContext, request: RagAdminUserUpsertRequest): RagAdminUserResponse {
        val normalizedUsername = normalizeUsername(
            request.username ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "username is required")
        )
        if (accountStore.findUser(normalizedUsername) != null) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "User '$normalizedUsername' already exists")
        }
        val normalizedRoles = normalizeRoles(request.roles).ifEmpty { linkedSetOf("ADMIN") }
        val password = request.password?.trim().orEmpty()
        if (password.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Password is required for new users")
        }
        val stored = RagAdminStoredUser(
            username = normalizedUsername,
            passwordHash = BCrypt.hashpw(password, BCrypt.gensalt()),
            displayName = normalizeDisplayName(request.displayName),
            enabled = request.enabled ?: true,
            roles = normalizedRoles
        )
        ensureAdminCoverage(stored, deletingUsername = null, previousUser = null)
        accountStore.upsertUser(
            username = stored.username,
            passwordHash = stored.passwordHash,
            displayName = stored.displayName,
            enabled = stored.enabled,
            roles = stored.roles
        )
        auditSink.recordUserAudit(
            action = "CREATE",
            actorUsername = actor.username,
            actorRole = actor.role,
            targetUsername = normalizedUsername,
            success = true,
            message = "created user",
            details = userSnapshotDetails(stored) + mapOf("roles" to stored.roles.sorted().joinToString(","))
        )
        return requireUser(normalizedUsername).toResponse()
    }

    fun updateUser(
        actor: RagAdminAccessContext,
        username: String,
        request: RagAdminUserUpsertRequest
    ): RagAdminUserResponse {
        val normalizedUsername = normalizeUsername(username)
        val existing = requireUser(normalizedUsername)
        val newPasswordHash = request.password
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { BCrypt.hashpw(it, BCrypt.gensalt()) }
            ?: existing.passwordHash
        val updated = RagAdminStoredUser(
            username = existing.username,
            passwordHash = newPasswordHash,
            displayName = normalizeDisplayName(request.displayName) ?: existing.displayName,
            enabled = request.enabled ?: existing.enabled,
            roles = if (request.roles.isEmpty()) existing.roles else normalizeRoles(request.roles)
                .ifEmpty { existing.roles }
        )
        ensureAdminCoverage(updated, deletingUsername = null, previousUser = existing)
        val changes = diffDetails(existing, updated)
        accountStore.upsertUser(
            username = updated.username,
            passwordHash = updated.passwordHash,
            displayName = updated.displayName,
            enabled = updated.enabled,
            roles = updated.roles
        )
        auditSink.recordUserAudit(
            action = "UPDATE",
            actorUsername = actor.username,
            actorRole = actor.role,
            targetUsername = normalizedUsername,
            success = true,
            message = if (changes.isEmpty()) "updated user settings" else "updated ${changes.size} field${if (changes.size == 1) "" else "s"}",
            details = changes
        )
        return requireUser(normalizedUsername).toResponse()
    }

    fun deleteUser(actor: RagAdminAccessContext, username: String) {
        val normalizedUsername = normalizeUsername(username)
        val existing = requireUser(normalizedUsername)
        ensureAdminCoverage(existing, deletingUsername = normalizedUsername, previousUser = existing)
        if (!accountStore.deleteUser(normalizedUsername)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "User '$normalizedUsername' not found")
        }
        auditSink.recordUserAudit(
            action = "DELETE",
            actorUsername = actor.username,
            actorRole = actor.role,
            targetUsername = normalizedUsername,
            success = true,
            message = "deleted user",
            details = userSnapshotDetails(existing)
        )
    }

    fun resetPassword(
        actor: RagAdminAccessContext,
        username: String,
        newPassword: String
    ): RagAdminUserResponse {
        val normalizedUsername = normalizeUsername(username)
        val existing = requireUser(normalizedUsername)
        val password = newPassword.trim()
        if (password.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must not be blank")
        }
        accountStore.upsertUser(
            username = existing.username,
            passwordHash = BCrypt.hashpw(password, BCrypt.gensalt()),
            displayName = existing.displayName,
            enabled = existing.enabled,
            roles = existing.roles
        )
        auditSink.recordUserAudit(
            action = "RESET_PASSWORD",
            actorUsername = actor.username,
            actorRole = actor.role,
            targetUsername = normalizedUsername,
            success = true,
            message = "password reset",
            details = mapOf(
                "password" to "updated",
                "roles" to existing.roles.sorted().joinToString(","),
                "enabled" to existing.enabled.toString()
            )
        )
        return requireUser(normalizedUsername).toResponse()
    }

    private fun requireUser(username: String): RagAdminStoredUser =
        accountStore.findUser(username)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "User '$username' not found")

    private fun ensureAdminCoverage(
        candidate: RagAdminStoredUser,
        deletingUsername: String?,
        previousUser: RagAdminStoredUser?
    ) {
        if (!properties.security.enabled) {
            return
        }
        val projectedUsers = buildList {
            if (deletingUsername == null && previousUser == null) {
                add(candidate)
            }
            accountStore.allUsers().forEach { user ->
                when {
                    deletingUsername != null && user.username == deletingUsername -> Unit
                    previousUser != null && user.username == previousUser.username -> add(candidate)
                    else -> add(user)
                }
            }
        }
        val enabledAdmins = projectedUsers.count { it.enabled && "ADMIN" in it.roles }
        if (enabledAdmins <= 0) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "At least one enabled ADMIN user must exist")
        }
    }

    private fun RagAdminStoredUser.toResponse(): RagAdminUserResponse =
        RagAdminUserResponse(
            username = username,
            displayName = displayName,
            enabled = enabled,
            roles = roles.sorted(),
            passwordConfigured = passwordHash.isNotBlank(),
            isAdmin = "ADMIN" in roles
        )

    private fun normalizeUsername(username: String): String {
        val normalized = username.trim()
        require(normalized.isNotBlank()) { "username must not be blank" }
        return normalized
    }

    private fun normalizeDisplayName(value: String?): String? =
        value?.trim()?.takeIf { it.isNotBlank() }

    private fun normalizeRoles(roles: Collection<String>): Set<String> =
        roles.asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { it.uppercase() }
            .toCollection(linkedSetOf())

    private fun userSnapshotDetails(user: RagAdminStoredUser): Map<String, String> = linkedMapOf(
        "username" to user.username,
        "displayName" to (user.displayName ?: "-"),
        "enabled" to user.enabled.toString(),
        "roles" to user.roles.sorted().joinToString(",")
    )

    private fun diffDetails(
        before: RagAdminStoredUser,
        after: RagAdminStoredUser
    ): Map<String, String> {
        val details = linkedMapOf<String, String>()
        if (before.displayName != after.displayName) {
            details["displayName"] = "${before.displayName ?: "-"} -> ${after.displayName ?: "-"}"
        }
        if (before.enabled != after.enabled) {
            details["enabled"] = "${before.enabled} -> ${after.enabled}"
        }
        if (before.roles.sorted() != after.roles.sorted()) {
            details["roles"] = "${before.roles.sorted().joinToString(",")} -> ${after.roles.sorted().joinToString(",")}"
        }
        if (before.passwordHash != after.passwordHash) {
            details["password"] = "updated"
        }
        return details
    }

    private fun matchesQuery(user: RagAdminStoredUser, query: String): Boolean {
        val haystack = buildString {
            append(user.username)
            append(' ')
            append(user.displayName.orEmpty())
            append(' ')
            append(user.roles.joinToString(" "))
            append(' ')
            append(if (user.enabled) "enabled" else "disabled")
        }.lowercase()
        return haystack.contains(query)
    }

    private fun userComparator(sort: String?, direction: String?): Comparator<RagAdminStoredUser> {
        val descending = direction?.trim()?.equals("desc", ignoreCase = true) == true
        val comparator = when (sort?.trim()?.lowercase()) {
            "displayname" -> compareBy<RagAdminStoredUser> { it.displayName?.lowercase().orEmpty() }
            "enabled" -> compareBy<RagAdminStoredUser> { if (it.enabled) 1 else 0 }
            "roles" -> compareBy<RagAdminStoredUser> { it.roles.sorted().joinToString(",").lowercase() }
            else -> compareBy<RagAdminStoredUser> { it.username.lowercase() }
        }.thenBy { it.username.lowercase() }
        return if (descending) comparator.reversed() else comparator
    }
}
