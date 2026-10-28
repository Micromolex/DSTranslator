package com.nya.dstranslate

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.nya.dstranslate.overlay.TranslateBubbleService

/**
 * Следит за жизненным циклом всех Activity и управляет видимостью пузыря.
 *
 * Пока приложение открыто (хотя бы одна Activity в состоянии resumed), пузырь
 * скрыт и не мешает интерфейсу. Как только приложение уходит в фон — пузырь
 * снова появляется. Сама служба при этом не останавливается.
 */
class DsApplication : Application() {

    private var resumedActivities = 0

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                resumedActivities++
                if (resumedActivities == 1) {
                    TranslateBubbleService.onAppForegroundChanged(true)
                }
            }

            override fun onActivityPaused(activity: Activity) {
                // Счётчик защищает от мигания пузыря при переходе между
                // Activity приложения (например, экран «Перевести»).
                resumedActivities = (resumedActivities - 1).coerceAtLeast(0)
                if (resumedActivities == 0) {
                    TranslateBubbleService.onAppForegroundChanged(false)
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
