package info.nightscout.pump.danars.di

import dagger.Module
import dagger.android.ContributesAndroidInjector
import info.nightscout.pump.danars.services.ZhiKaiService

@Module
@Suppress("unused")
abstract class ZhiKaiServiceModule {
    @ContributesAndroidInjector abstract fun contributesZhiKaiService(): ZhiKaiService
}