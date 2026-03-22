package com.ainsoft.rag.spring

class RagAdminUserUpsertRequest() {
    var username: String? = null
    var displayName: String? = null
    var password: String? = null
    var enabled: Boolean? = null
    var roles: List<String> = emptyList()

    constructor(
        username: String? = null,
        displayName: String? = null,
        password: String? = null,
        enabled: Boolean? = null,
        roles: List<String> = emptyList()
    ) : this() {
        this.username = username
        this.displayName = displayName
        this.password = password
        this.enabled = enabled
        this.roles = roles
    }
}

class RagAdminUserPasswordResetRequest() {
    var password: String = ""

    constructor(password: String) : this() {
        this.password = password
    }
}

