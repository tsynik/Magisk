package com.topjohnwu.magisk.ui.superuser

import android.content.pm.PackageManager
import android.content.res.Resources
import androidx.databinding.ObservableArrayList
import androidx.lifecycle.viewModelScope
import com.topjohnwu.magisk.BR
import com.topjohnwu.magisk.R
import com.topjohnwu.magisk.arch.BaseViewModel
import com.topjohnwu.magisk.arch.adapterOf
import com.topjohnwu.magisk.arch.diffListOf
import com.topjohnwu.magisk.arch.itemBindingOf
import com.topjohnwu.magisk.core.magiskdb.PolicyDao
import com.topjohnwu.magisk.core.model.su.SuPolicy
import com.topjohnwu.magisk.core.utils.BiometricHelper
import com.topjohnwu.magisk.core.utils.currentLocale
import com.topjohnwu.magisk.databinding.ComparableRvItem
import com.topjohnwu.magisk.events.SnackbarEvent
import com.topjohnwu.magisk.events.dialog.BiometricDialog
import com.topjohnwu.magisk.events.dialog.SuperuserRevokeDialog
import com.topjohnwu.magisk.view.TappableHeadlineItem
import com.topjohnwu.magisk.view.TextItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.tatarka.bindingcollectionadapter2.collections.MergeObservableList

class SuperuserViewModel(
    private val db: PolicyDao,
    private val packageManager: PackageManager,
    private val resources: Resources
) : BaseViewModel(), TappableHeadlineItem.Listener {

    private val itemNoData = TextItem(R.string.superuser_policy_none)

    private val itemsPolicies = diffListOf<PolicyRvItem>()
    private val itemsHelpers = ObservableArrayList<TextItem>()

    val adapter = adapterOf<ComparableRvItem<*>>()
    val items = MergeObservableList<ComparableRvItem<*>>()
        .insertItem(TappableHeadlineItem.Hide)
        .insertList(itemsHelpers)
        .insertList(itemsPolicies)
    val itemBinding = itemBindingOf<ComparableRvItem<*>> {
        it.bindExtra(BR.listener, this)
    }

    // ---

    override fun refresh() = viewModelScope.launch {
        state = State.LOADING
        val (policies, diff) = withContext(Dispatchers.Default) {
            val policies = db.fetchAll {
                PolicyRvItem(it, it.applicationInfo.loadIcon(packageManager), this@SuperuserViewModel)
            }.sortedWith(compareBy(
                { it.item.appName.toLowerCase(currentLocale) },
                { it.item.packageName }
            ))
            policies to itemsPolicies.calculateDiff(policies)
        }
        itemsPolicies.update(policies, diff)
        if (itemsPolicies.isNotEmpty())
            itemsHelpers.clear()
        else if (itemsHelpers.isEmpty())
            itemsHelpers.add(itemNoData)
        state = State.LOADED
    }

    // ---

    override fun onItemPressed(item: TappableHeadlineItem) = when (item) {
        TappableHeadlineItem.Hide -> hidePressed()
        else -> Unit
    }

    private fun hidePressed() =
        SuperuserFragmentDirections.actionSuperuserFragmentToHideFragment().publish()

    fun deletePressed(item: PolicyRvItem) {
        fun updateState() = viewModelScope.launch {
            db.delete(item.item.uid)
            itemsPolicies.removeAll { it.genericItemSameAs(item) }
            if (itemsPolicies.isEmpty() && itemsHelpers.isEmpty()) {
                itemsHelpers.add(itemNoData)
            }
        }

        if (BiometricHelper.isEnabled) {
            BiometricDialog {
                onSuccess { updateState() }
            }.publish()
        } else {
            SuperuserRevokeDialog {
                appName = item.item.appName
                onSuccess { updateState() }
            }.publish()
        }
    }

    //---

    fun updatePolicy(policy: SuPolicy, isLogging: Boolean) = viewModelScope.launch {
        db.update(policy)
        val str = when {
            isLogging -> when {
                policy.logging -> R.string.su_snack_log_on
                else -> R.string.su_snack_log_off
            }
            else -> when {
                policy.notification -> R.string.su_snack_notif_on
                else -> R.string.su_snack_notif_off
            }
        }
        SnackbarEvent(resources.getString(str, policy.appName)).publish()
    }

    fun togglePolicy(item: PolicyRvItem, enable: Boolean) {
        fun updateState() {
            item.policyState = enable

            val policy = if (enable) SuPolicy.ALLOW else SuPolicy.DENY
            val app = item.item.copy(policy = policy)

            viewModelScope.launch {
                db.update(app)
                val res = if (app.policy == SuPolicy.ALLOW) R.string.su_snack_grant
                else R.string.su_snack_deny
                SnackbarEvent(resources.getString(res).format(item.item.appName)).publish()
            }
        }

        if (BiometricHelper.isEnabled) {
            BiometricDialog {
                onSuccess { updateState() }
            }.publish()
        } else {
            updateState()
        }
    }
}
