package vip.ringling.recon.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton
import vip.ringling.recon.data.HistoryRepository
import vip.ringling.recon.data.RoomHistoryRepository
import vip.ringling.recon.data.secret.ApiKeys
import vip.ringling.recon.data.secret.KeystoreApiKeys
import vip.ringling.recon.data.sources.breach.BreachSource
import vip.ringling.recon.data.sources.domain.DomainSource
import vip.ringling.recon.data.mock.MockIdentitySource
import vip.ringling.recon.data.mock.MockRegistrySource
import vip.ringling.recon.domain.OsintSource

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {
    @Binds @Singleton abstract fun history(impl: RoomHistoryRepository): HistoryRepository
    @Binds @Singleton abstract fun apiKeys(impl: KeystoreApiKeys): ApiKeys

    // Phase 2: replace each Mock* with the real source; UI is unaffected.
    @Binds @IntoSet abstract fun breach(s: BreachSource): OsintSource
    @Binds @IntoSet abstract fun identity(s: MockIdentitySource): OsintSource
    @Binds @IntoSet abstract fun domain(s: DomainSource): OsintSource
    @Binds @IntoSet abstract fun registry(s: MockRegistrySource): OsintSource
}
