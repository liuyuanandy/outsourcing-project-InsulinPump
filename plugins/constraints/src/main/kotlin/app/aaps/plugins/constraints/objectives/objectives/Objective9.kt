package app.aaps.plugins.constraints.objectives.objectives

import app.aaps.core.interfaces.utils.T
import app.aaps.plugins.constraints.R
import dagger.android.HasAndroidInjector
/**
 * 目标 9
 */
class Objective9(injector: HasAndroidInjector) : Objective(injector, "smb", R.string.objectives_smb_objective, R.string.objectives_smb_gate) {

    init {
        if(isTest){
            tasks.add(
                MinimumDurationTask(this, T.days(0).msecs())
                    .learned(Learned(R.string.objectives_smb_learned))
            )
        }else{
            tasks.add(
                MinimumDurationTask(this, T.days(28).msecs())
                    .learned(Learned(R.string.objectives_smb_learned))
            )
        }

    }
}