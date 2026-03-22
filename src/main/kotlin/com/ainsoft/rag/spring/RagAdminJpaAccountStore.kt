package com.ainsoft.rag.spring

import jakarta.persistence.EntityManager
import org.springframework.transaction.annotation.Transactional

open class RagAdminJpaAccountStore(
    private val entityManager: EntityManager
) : RagAdminAccountStore {
    @Transactional
    override fun findUser(username: String): RagAdminStoredUser? =
        entityManager.find(RagAdminUserEntity::class.java, username)?.toStoredUser()

    @Transactional
    override fun upsertUser(
        username: String,
        passwordHash: String,
        displayName: String?,
        enabled: Boolean,
        roles: Set<String>
    ) {
        val normalizedRoles = normalizeRoles(roles)
        val existing = entityManager.find(RagAdminUserEntity::class.java, username)
        if (existing == null) {
            entityManager.persist(
                RagAdminUserEntity(
                    username = username,
                    passwordHash = passwordHash,
                    displayName = displayName,
                    enabled = enabled,
                    roles = normalizedRoles.toCollection(linkedSetOf())
                )
            )
        } else {
            existing.updateFrom(
                passwordHash = passwordHash,
                displayName = displayName,
                enabled = enabled,
                roles = normalizedRoles
            )
        }
    }

    @Transactional
    override fun allUsers(): List<RagAdminStoredUser> {
        val query = entityManager.createQuery(
            "select u from RagAdminUserEntity u order by u.username",
            RagAdminUserEntity::class.java
        )
        return query.resultList.map { it.toStoredUser() }
    }

    @Transactional
    override fun deleteUser(username: String): Boolean {
        val existing = entityManager.find(RagAdminUserEntity::class.java, username) ?: return false
        entityManager.remove(existing)
        return true
    }

    private fun normalizeRoles(roles: Set<String>): Set<String> =
        roles.asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { it.uppercase() }
            .toCollection(linkedSetOf())
}
