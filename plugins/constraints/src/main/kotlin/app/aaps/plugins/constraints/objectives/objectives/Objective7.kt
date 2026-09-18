package app.aaps.plugins.constraints.objectives.objectives

import app.aaps.core.interfaces.utils.T
import app.aaps.plugins.constraints.R
import dagger.android.HasAndroidInjector
/**
 * 目标 8
 */
class Objective7(injector: HasAndroidInjector) : Objective(injector, "autosens", R.string.objectives_autosens_objective, R.string.objectives_autosens_gate) {

    init {
        if(isTest){
            tasks.add(
                MinimumDurationTask(this, T.days(0).msecs())
                    .learned(Learned(R.string.objectives_autosens_learned))
            )
        }else{
            tasks.add(
                MinimumDurationTask(this, T.days(7).msecs())
                    .learned(Learned(R.string.objectives_autosens_learned))
            )
        }

    }
}