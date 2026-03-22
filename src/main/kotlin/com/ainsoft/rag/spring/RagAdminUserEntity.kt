package com.ainsoft.rag.spring

import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.Table

@Entity
@Table(name = "rag_admin_users")
open class RagAdminUserEntity(
    @Id
    @Column(name = "username", nullable = false, updatable = false, length = 128)
    open var username: String = "",

    @Column(name = "password_hash", nullable = false, length = 255)
    open var passwordHash: String = "",

    @Column(name = "display_name", length = 255)
    open var displayName: String? = null,

    @Column(name = "enabled", nullable = false)
    open var enabled: Boolean = true,

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
        name = "rag_admin_user_roles",
        joinColumns = [JoinColumn(name = "username")]
    )
    @Column(name = "role", nullable = false, length = 64)
    open var roles: MutableSet<String> = linkedSetOf()
) {
    fun toStoredUser(): RagAdminStoredUser =
        RagAdminStoredUser(
            username = username,
            passwordHash = passwordHash,
            displayName = displayName,
            enabled = enabled,
            roles = roles.toSet()
        )

    fun updateFrom(
        passwordHash: String,
        displayName: String?,
        enabled: Boolean,
        roles: Set<String>
    ) {
        this.passwordHash = passwordHash
        this.displayName = displayName
        this.enabled = enabled
        this.roles.clear()
        this.roles.addAll(roles)
    }

    companion object {
        fun fromStoredUser(user: RagAdminStoredUser): RagAdminUserEntity =
            RagAdminUserEntity(
                username = user.username,
                passwordHash = user.passwordHash,
                displayName = user.displayName,
                enabled = user.enabled,
                roles = user.roles.toCollection(linkedSetOf())
            )
    }
}
