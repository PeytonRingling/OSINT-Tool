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
import vip.ringling.recon.data.sources.identity.AssetSiteList
import vip.ringling.recon.data.sources.identity.IdentitySource
import vip.ringling.recon.data.sources.identity.SiteListProvider
import vip.ringling.recon.data.sources.registry.IdahoRegistryAdapter
import vip.ringling.recon.data.sources.registry.RegistryAdapter
import vip.ringling.recon.data.sources.registry.RegistrySource
import vip.ringling.recon.domain.OsintSource

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {
    @Binds @Singleton abstract fun history(impl: RoomHistoryRepository): HistoryRepository
    @Binds @Singleton abstract fun apiKeys(impl: KeystoreApiKeys): ApiKeys

    @Binds @IntoSet abstract fun breach(s: BreachSource): OsintSource
    @Binds @IntoSet abstract fun identity(s: IdentitySource): OsintSource
    @Binds @Singleton abstract fun siteList(s: AssetSiteList): SiteListProvider
    @Binds @IntoSet abstract fun domain(s: DomainSource): OsintSource
    @Binds @IntoSet abstract fun registry(s: RegistrySource): OsintSource
    @Binds @IntoSet abstract fun idaho(a: IdahoRegistryAdapter): RegistryAdapter
}
