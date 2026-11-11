package com.nuvio.tv.core.di

import com.nuvio.tv.core.backup.*
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class BackupModule {
    @Binds abstract fun backup(service: ConfigurationBackupService): BackupService
    @Binds abstract fun restore(service: ConfigurationBackupService): RestoreService
}
