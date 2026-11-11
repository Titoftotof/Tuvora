package com.nuvio.tv.core.di

import com.nuvio.tv.core.security.KeystoreSecureCredentialStore
import com.nuvio.tv.core.security.SecureCredentialStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SecurityModule {

    @Binds
    @Singleton
    abstract fun bindSecureCredentialStore(
        impl: KeystoreSecureCredentialStore
    ): SecureCredentialStore
}
