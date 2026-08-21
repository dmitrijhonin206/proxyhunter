package com.proxyhunter.telegram.di

import com.proxyhunter.telegram.data.repository.ProxyRepositoryImpl
import com.proxyhunter.telegram.domain.repository.ProxyRepository
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
    abstract fun bindProxyRepository(impl: ProxyRepositoryImpl): ProxyRepository
}
