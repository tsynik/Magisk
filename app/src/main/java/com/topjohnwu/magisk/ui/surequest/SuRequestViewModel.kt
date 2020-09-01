package com.topjohnwu.magisk.ui.surequest

import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.os.CountDownTimer
import androidx.databinding.Bindable
import androidx.lifecycle.viewModelScope
import com.topjohnwu.magisk.BR
import com.topjohnwu.magisk.R
import com.topjohnwu.magisk.arch.BaseViewModel
import com.topjohnwu.magisk.core.Config
import com.topjohnwu.magisk.core.magiskdb.PolicyDao
import com.topjohnwu.magisk.core.model.su.SuPolicy.Companion.ALLOW
import com.topjohnwu.magisk.core.model.su.SuPolicy.Companion.DENY
import com.topjohnwu.magisk.core.su.SuRequestHandler
import com.topjohnwu.magisk.core.utils.BiometricHelper
import com.topjohnwu.magisk.events.DieEvent
import com.topjohnwu.magisk.ui.superuser.SpinnerRvItem
import com.topjohnwu.magisk.utils.set
import com.topjohnwu.superuser.internal.UiThreadHandler
import kotlinx.coroutines.launch
import me.tatarka.bindingcollectionadapter2.BindingListViewAdapter
import me.tatarka.bindingcollectionadapter2.ItemBinding
import java.util.concurrent.TimeUnit.SECONDS

class SuRequestViewModel(
    private val pm: PackageManager,
    private val policyDB: PolicyDao,
    private val timeoutPrefs: SharedPreferences,
    private val res: Resources
) : BaseViewModel() {

    @get:Bindable
    var icon: Drawable? = null
        set(value) = set(value, field, { field = it }, BR.icon)

    @get:Bindable
    var title = ""
        set(value) = set(value, field, { field = it }, BR.title)

    @get:Bindable
    var packageName = ""
        set(value) = set(value, field, { field = it }, BR.packageName)

    @get:Bindable
    var denyText = res.getString(R.string.deny)
        set(value) = set(value, field, { field = it }, BR.denyText)

    @get:Bindable
    var warningText = res.getString(R.string.su_warning)
        set(value) = set(value, field, { field = it }, BR.warningText)

    @get:Bindable
    var selectedItemPosition = 0
        set(value) = set(value, field, { field = it }, BR.selectedItemPosition)

    @get:Bindable
    var grantEnabled = false
        set(value) = set(value, field, { field = it }, BR.grantEnabled)

    private val items = res.getStringArray(R.array.allow_timeout).map { SpinnerRvItem(it) }
    val adapter = BindingListViewAdapter<SpinnerRvItem>(1).apply {
        itemBinding = ItemBinding.of { binding, _, item ->
            item.bind(binding)
        }
        setItems(items)
    }

    private val handler = Handler()

    fun grantPressed() {
        handler.cancelTimer()
        if (BiometricHelper.isEnabled) {
            withView {
                BiometricHelper.authenticate(this) {
                    handler.respond(ALLOW)
                }
            }
        } else {
            handler.respond(ALLOW)
        }
    }

    fun denyPressed() {
        handler.respond(DENY)
    }

    fun spinnerTouched(): Boolean {
        handler.cancelTimer()
        return false
    }

    fun handleRequest(intent: Intent) {
        viewModelScope.launch {
            if (!handler.start(intent))
                DieEvent().publish()
        }
    }

    private inner class Handler : SuRequestHandler(pm, policyDB) {

        private lateinit var timer: CountDownTimer

        fun respond(action: Int) {
            timer.cancel()

            val pos = selectedItemPosition
            timeoutPrefs.edit().putInt(policy.packageName, pos).apply()
            respond(action, Config.Value.TIMEOUT_LIST[pos])

            // Kill activity after response
            DieEvent().publish()
        }

        fun cancelTimer() {
            timer.cancel()
            denyText = res.getString(R.string.deny)
        }

        override fun onStart() {
            icon = policy.applicationInfo.loadIcon(pm)
            title = policy.appName
            packageName = policy.packageName
            UiThreadHandler.handler.post {
                // Delay is required to properly do selection
                selectedItemPosition = timeoutPrefs.getInt(policy.packageName, 0)
            }

            // Set timer
            val millis = SECONDS.toMillis(Config.suDefaultTimeout.toLong())
            timer = SuTimer(millis, 1000).apply { start() }
        }

        private inner class SuTimer(
            private val millis: Long,
            interval: Long
        ) : CountDownTimer(millis, interval) {

            override fun onTick(remains: Long) {
                if (!grantEnabled && remains <= millis - 1000) {
                    grantEnabled = true
                }
                denyText = "${res.getString(R.string.deny)} (${(remains / 1000) + 1})"
            }

            override fun onFinish() {
                denyText = res.getString(R.string.deny)
                respond(DENY)
            }

        }
    }

}
