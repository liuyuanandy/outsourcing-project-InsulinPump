package info.nightscout.pump.danars.di

import dagger.Module

@Module(includes = [
    DanaRSCommModule::class,
    DanaRSActivitiesModule::class,
    DanaRSServicesModule::class,
    ZhiKaiServiceModule::class,
])
open class DanaRSModule