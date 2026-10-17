package vip.ringling.recon.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton
import vip.ringling.recon.data.HistoryRepository
import vip.ringling.recon.data.RoomHistoryRepository
import vip.ringling.recon.data.location.AndroidUserLocation
import vip.ringling.recon.data.location.UserLocation
import vip.ringling.recon.data.secret.ApiKeys
import vip.ringling.recon.data.secret.KeystoreApiKeys
import vip.ringling.recon.data.sources.breach.BreachSource
import vip.ringling.recon.data.sources.domain.DomainSource
import vip.ringling.recon.data.sources.phone.PhoneSource
import vip.ringling.recon.data.sources.image.ImageSource
import vip.ringling.recon.data.sources.network.IpSource
import vip.ringling.recon.data.sources.crypto.WalletSource
import vip.ringling.recon.data.sources.code.GithubSource
import vip.ringling.recon.data.sources.dorks.DorkSource
import vip.ringling.recon.data.sources.emailintel.EmailIntelSource
import vip.ringling.recon.data.sources.emailintel.AssetDisposableDomains
import vip.ringling.recon.data.sources.emailintel.DisposableDomains
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
    @Binds @Singleton abstract fun userLocation(impl: AndroidUserLocation): UserLocation
    @Binds @Singleton abstract fun apiKeys(impl: KeystoreApiKeys): ApiKeys

    @Binds @IntoSet abstract fun breach(s: BreachSource): OsintSource
    @Binds @IntoSet abstract fun identity(s: IdentitySource): OsintSource
    @Binds @Singleton abstract fun siteList(s: AssetSiteList): SiteListProvider
    @Binds @Singleton abstract fun siteHealth(s: vip.ringling.recon.data.sources.identity.PrefsSiteHealth): vip.ringling.recon.data.sources.identity.SiteHealth
    @Binds @IntoSet abstract fun phone(s: PhoneSource): OsintSource
    @Binds @IntoSet abstract fun phonePaid(s: PhoneSource): vip.ringling.recon.domain.PaidLookups
    @Binds @IntoSet abstract fun image(s: ImageSource): OsintSource
    @Binds @IntoSet abstract fun ip(s: IpSource): OsintSource
    @Binds @IntoSet abstract fun wallet(s: WalletSource): OsintSource
    @Binds @IntoSet abstract fun github(s: GithubSource): OsintSource
    @Binds @IntoSet abstract fun dorks(s: DorkSource): OsintSource
    @Binds @IntoSet abstract fun emailIntel(s: EmailIntelSource): OsintSource
    @Binds abstract fun disposable(s: AssetDisposableDomains): DisposableDomains
    @Binds @IntoSet abstract fun domain(s: DomainSource): OsintSource
    @Binds @IntoSet abstract fun lookalikePaid(s: vip.ringling.recon.data.sources.domain.LookalikeLookup): vip.ringling.recon.domain.PaidLookups
    @Binds @IntoSet abstract fun people(s: vip.ringling.recon.data.sources.people.PeopleSource): OsintSource
    @Binds @IntoSet abstract fun registry(s: RegistrySource): OsintSource
    @Binds @IntoSet abstract fun idaho(a: IdahoRegistryAdapter): RegistryAdapter
}
