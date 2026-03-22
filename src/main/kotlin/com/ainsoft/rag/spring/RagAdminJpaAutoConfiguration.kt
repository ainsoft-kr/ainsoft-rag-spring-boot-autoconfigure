package com.ainsoft.rag.spring

import jakarta.persistence.EntityManager
import jakarta.persistence.EntityManagerFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

@AutoConfiguration(before = [RagAdminAutoConfiguration::class])
@ConditionalOnClass(name = ["jakarta.persistence.EntityManager", "org.springframework.transaction.annotation.Transactional"])
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(RagAdminProperties::class)
class RagAdminJpaAutoConfiguration {
    @Bean
    @ConditionalOnBean(EntityManagerFactory::class)
    @ConditionalOnMissingBean
    fun ragAdminJpaAccountStore(entityManager: EntityManager): RagAdminAccountStore =
        RagAdminJpaAccountStore(entityManager)
}
