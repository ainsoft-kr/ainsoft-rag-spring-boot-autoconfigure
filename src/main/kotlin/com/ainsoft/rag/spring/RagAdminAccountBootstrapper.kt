package com.ainsoft.rag.spring

import jakarta.annotation.PostConstruct
import org.mindrot.jbcrypt.BCrypt

class RagAdminAccountBootstrapper(
    private val properties: RagAdminProperties,
    private val accountStore: RagAdminAccountStore
) {
    @PostConstruct
    fun seedAccounts() {
        if (!properties.security.enabled) {
            return
        }
        properties.security.users.forEach { (username, user) ->
            if (accountStore.findUser(username) == null) {
                val normalizedRoles = user.roles.asSequence()
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .map { it.uppercase() }
                    .toCollection(linkedSetOf())
                    .ifEmpty { linkedSetOf("ADMIN") }
                accountStore.upsertUser(
                    username = username,
                    passwordHash = hashPassword(user.password),
                    displayName = user.displayName,
                    enabled = true,
                    roles = normalizedRoles
                )
            }
        }
    }

    private fun hashPassword(password: String): String =
        if (password.startsWith("$2a$") || password.startsWith("$2b$") || password.startsWith("$2y$")) {
            password
        } else {
            BCrypt.hashpw(password, BCrypt.gensalt())
        }
}
