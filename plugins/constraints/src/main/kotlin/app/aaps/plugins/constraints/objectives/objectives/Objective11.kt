package app.aaps.plugins.constraints.objectives.objectives

import app.aaps.core.interfaces.utils.T
import app.aaps.plugins.constraints.R
import dagger.android.HasAndroidInjector
/**
 * 目标 11
 */
class Objective11(injector: HasAndroidInjector) : Objective(injector, "dyn_isf", R.string.objectives_dyn_isf_objective, R.string.objectives_dyn_isf_gate) {

    init {
        if(isTest){
            tasks.add(
                MinimumDurationTask(this, T.days(0).msecs())
                    .learned(Learned(R.string.objectives_dyn_isf_learned))
            )
        }else{
            tasks.add(
                MinimumDurationTask(this, T.days(28).msecs())
                    .learned(Learned(R.string.objectives_dyn_isf_learned))
            )
        }

    }
}