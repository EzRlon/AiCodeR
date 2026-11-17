package com.aicode.di

import com.aicode.core.watch.WorkspaceWatchContext
import com.aicode.feature.settings.domain.repository.AIProviderRepository
import com.aicode.feature.settings.data.repository.AIProviderRepositoryImpl
import com.aicode.feature.credentials.domain.repository.CredentialRepository
import com.aicode.feature.credentials.data.repository.FileCredentialRepository
import com.aicode.feature.memorygraph.data.GraphRepository
import com.aicode.feature.memorygraph.domain.ConclusionReader
import com.aicode.feature.workspace.domain.WorkspaceWatchContextImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindAIProviderRepository(
        aiProviderRepositoryImpl: AIProviderRepositoryImpl
    ): AIProviderRepository

    @Binds
    @Singleton
    abstract fun bindCredentialRepository(
        fileCredentialRepository: FileCredentialRepository
    ): CredentialRepository

    /** 图谱读取侧契约：agent 域层只依赖 [ConclusionReader]，不直连 data 层的 GraphRepository。 */
    @Binds
    @Singleton
    abstract fun bindConclusionReader(graphRepository: GraphRepository): ConclusionReader

    /** core 的 FileChangeHub 只依赖 [WorkspaceWatchContext]；工作区/容器语义由 feature 实现。 */
    @Binds
    @Singleton
    abstract fun bindWorkspaceWatchContext(
        impl: WorkspaceWatchContextImpl
    ): WorkspaceWatchContext
}
