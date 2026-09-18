package info.nightscout.pump.danars.di

import dagger.Module

@Module(includes = [
    DanaRSCommModule::class,
    DanaRSActivitiesModule::class,
    ZhiKaiServiceModule::class,
])
open class ZhiKaiModule