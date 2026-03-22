package com.ainsoft.rag.spring

import com.ainsoft.rag.api.RagConfig
import com.ainsoft.rag.api.RagEngine
import com.ainsoft.rag.embeddings.EmbeddingProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@AutoConfiguration(after = [RagAutoConfiguration::class])
@ConditionalOnClass(name = ["org.springframework.web.servlet.DispatcherServlet"])
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnBean(RagEngine::class)
@ConditionalOnProperty(prefix = "rag.admin", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(RagAdminProperties::class)
class RagAdminAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun ragAdminUiController(
        properties: RagAdminProperties,
        securityService: RagAdminSecurityService
    ): RagAdminUiController =
        RagAdminUiController(properties, securityService)

    @Bean
    @ConditionalOnMissingBean
    fun ragAdminAuthController(
        properties: RagAdminProperties,
        securityService: RagAdminSecurityService
    ): RagAdminAuthController = RagAdminAuthController(properties, securityService)

    @Bean
    @ConditionalOnMissingBean
    fun ragAdminService(
        engine: RagEngine,
        properties: RagProperties,
        adminProperties: RagAdminProperties,
        ragConfig: RagConfig,
        embeddingProvider: EmbeddingProvider
    ): RagAdminService = RagAdminService(
        engine = engine,
        properties = properties,
        adminProperties = adminProperties,
        ragConfig = ragConfig,
        embeddingProvider = embeddingProvider
    )

    @Bean
    @ConditionalOnMissingBean
    fun ragAdminSecurityService(
        adminProperties: RagAdminProperties,
        adminService: RagAdminService,
        accountStore: RagAdminAccountStore
    ): RagAdminSecurityService = RagAdminSecurityService(adminProperties, adminService, accountStore)

    @Bean
    @ConditionalOnMissingBean
    fun ragAdminSecurityInterceptor(
        adminProperties: RagAdminProperties,
        securityService: RagAdminSecurityService,
        adminService: RagAdminService
    ): RagAdminSecurityInterceptor = RagAdminSecurityInterceptor(adminProperties, securityService, adminService)

    @Bean
    @ConditionalOnMissingBean(name = ["ragAdminWebMvcConfigurer"])
    fun ragAdminWebMvcConfigurer(
        adminProperties: RagAdminProperties,
        interceptor: RagAdminSecurityInterceptor
    ): WebMvcConfigurer = RagAdminWebMvcConfiguration(adminProperties, interceptor)

    @Bean
    @ConditionalOnMissingBean
    fun ragAdminApiController(
        engine: RagEngine,
        properties: RagProperties,
        ragConfig: RagConfig,
        embeddingProvider: EmbeddingProvider,
        adminService: RagAdminService,
        securityService: RagAdminSecurityService,
        accountManagementService: RagAdminAccountManagementService
    ): RagAdminApiController = RagAdminApiController(
        engine = engine,
        properties = properties,
        ragConfig = ragConfig,
        embeddingProvider = embeddingProvider,
        adminService = adminService,
        securityService = securityService,
        accountManagementService = accountManagementService
    )

    @Bean
    @ConditionalOnMissingBean
    fun ragAdminExceptionHandler(): RagAdminExceptionHandler = RagAdminExceptionHandler()

    @Bean
    @ConditionalOnMissingBean
    fun ragAdminAccountStore(): RagAdminAccountStore = InMemoryRagAdminAccountStore()

    @Bean
    @ConditionalOnMissingBean
    fun ragAdminAccountBootstrapper(
        adminProperties: RagAdminProperties,
        accountStore: RagAdminAccountStore
    ): RagAdminAccountBootstrapper = RagAdminAccountBootstrapper(adminProperties, accountStore)

    @Bean
    @ConditionalOnMissingBean
    fun ragAdminAccountManagementService(
        adminProperties: RagAdminProperties,
        accountStore: RagAdminAccountStore,
        adminService: RagAdminService
    ): RagAdminAccountManagementService = RagAdminAccountManagementService(adminProperties, accountStore, adminService)
}
