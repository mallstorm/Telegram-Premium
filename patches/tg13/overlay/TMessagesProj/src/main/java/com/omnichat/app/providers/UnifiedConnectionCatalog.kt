package com.omnichat.app.providers

/**
 * Runtime view that merges transport profiles and account setup metadata by stable provider ID.
 * This is the compatibility bridge away from the historical ProviderCatalog + AccountProviderCatalog
 * split: consumers can reason about one connection definition without losing either source yet.
 */
data class UnifiedConnectionDefinition(
    val id: String,
    val vendorId: String,
    val profile: ProviderProfile?,
    val accountSpec: AccountProviderSpec?,
    val channel: ConnectionChannel,
    val authKind: ConnectionAuthKind
) {
    val isAccountManaged: Boolean get() = accountSpec != null
    val isSelectable: Boolean get() = profile?.selectable != false
}

object UnifiedConnectionCatalog {
    fun merge(
        profiles: List<ProviderProfile>,
        accountSpecs: List<AccountProviderSpec>
    ): List<UnifiedConnectionDefinition> {
        val profilesById = profiles.associateBy { it.id }
        val accountsById = accountSpecs.associateBy { it.id }
        require(profilesById.size == profiles.size) { "Повторяющиеся ID transport-профилей" }
        require(accountsById.size == accountSpecs.size) { "Повторяющиеся ID account-профилей" }

        // During migration both legacy registries still exist. Fail fast if their duplicated transport
        // facts drift instead of silently offering two materially different definitions for one ID.
        profilesById.keys.intersect(accountsById.keys).forEach { id ->
            val profile = profilesById.getValue(id)
            val account = accountsById.getValue(id)
            val profileUrl = profile.baseUrl.trim().trimEnd('/')
            val accountUrl = account.baseUrl.trim().trimEnd('/')
            if (profileUrl.isNotBlank() && accountUrl.isNotBlank()) {
                require(profileUrl == accountUrl) {
                    "Конфликт Base URL для $id: provider=$profileUrl, account=$accountUrl"
                }
            }
            require(profile.protocol == account.protocol) {
                "Конфликт протокола для $id: provider=${profile.protocol}, account=${account.protocol}"
            }
        }

        val orderedIds = buildList {
            profiles.forEach { if (it.id !in this) add(it.id) }
            accountSpecs.forEach { if (it.id !in this) add(it.id) }
        }
        return orderedIds.map { id ->
            UnifiedConnectionDefinition(
                id = id,
                vendorId = ConnectionIdentity.canonicalVendorId(id),
                profile = profilesById[id],
                accountSpec = accountsById[id],
                channel = ConnectionIdentity.channel(id),
                authKind = ConnectionIdentity.authKind(id)
            )
        }
    }

    /** Generic API editor gets only definitions not owned by Accounts & subscriptions. */
    fun apiProfiles(
        profiles: List<ProviderProfile>,
        accountSpecs: List<AccountProviderSpec> = AccountProviderCatalog.specs
    ): List<ProviderProfile> = merge(profiles, accountSpecs)
        .asSequence()
        .filter { ConnectionIdentity.belongsInGenericApiSetup(it.id) }
        .mapNotNull { it.profile }
        .toList()

    fun accountDefinitions(
        profiles: List<ProviderProfile>,
        accountSpecs: List<AccountProviderSpec> = AccountProviderCatalog.specs
    ): List<UnifiedConnectionDefinition> = merge(profiles, accountSpecs)
        .filter { it.accountSpec != null }

    fun duplicateSourceIds(
        profiles: List<ProviderProfile>,
        accountSpecs: List<AccountProviderSpec> = AccountProviderCatalog.specs
    ): Set<String> {
        val profileIds = profiles.mapTo(hashSetOf()) { it.id }
        return accountSpecs.map { it.id }.filterTo(linkedSetOf()) { it in profileIds }
    }
}
