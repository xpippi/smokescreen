package com.frostnerd.smokescreen.fragment

import android.app.Activity.RESULT_OK
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import com.frostnerd.cacheadapter.ListDataSource
import com.frostnerd.cacheadapter.ModelAdapterBuilder
import com.frostnerd.general.service.isServiceRunning
import com.frostnerd.lifecyclemanagement.BaseViewHolder
import com.frostnerd.lifecyclemanagement.launchWithLifecycle
import com.frostnerd.smokescreen.*
import com.frostnerd.smokescreen.database.entities.DnsRule
import com.frostnerd.smokescreen.database.entities.HostSource
import com.frostnerd.smokescreen.database.getDatabase
import com.frostnerd.smokescreen.dialog.*
import com.frostnerd.smokescreen.service.DnsVpnService
import com.frostnerd.smokescreen.service.RuleExportService
import com.frostnerd.smokescreen.service.RuleImportService
import com.frostnerd.smokescreen.util.SpaceItemDecorator
import com.frostnerd.smokescreen.util.worker.RuleImportStartWorker
import com.google.android.material.snackbar.Snackbar
import kotlinx.android.synthetic.main.activity_dns_rules.*
import kotlinx.android.synthetic.main.item_datasource.view.*
import kotlinx.android.synthetic.main.item_datasource.view.cardContent
import kotlinx.android.synthetic.main.item_datasource.view.delete
import kotlinx.android.synthetic.main.item_datasource.view.enable
import kotlinx.android.synthetic.main.item_datasource.view.text
import kotlinx.android.synthetic.main.item_datasource_rules.view.*
import kotlinx.android.synthetic.main.item_dnsrule_host.view.*
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/*
 * Copyright (C) 2019 Daniel Wolf (Ch4t4r)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * You can contact the developer at daniel.wolf@frostnerd.com.
 */
class DnsRuleFragment : Fragment() {
    private lateinit var sourceAdapter: RecyclerView.Adapter<*>
    private lateinit var sourceAdapterList: MutableList<HostSource>
    private lateinit var adapterDataSource: ListDataSource<HostSource>
    private lateinit var userDnsRules:MutableList<DnsRule>
    private lateinit var sourceRuleCount:MutableMap<HostSource, Int?>
    private var importDoneReceiver:BroadcastReceiver? = null
    private var exportDoneReceiver:BroadcastReceiver? = null
    private var refreshProgressShown = false
    private var exportProgressShown = false
    private var fileChosenRequestCode = 5
    private var fileChosenCallback: ((Uri) -> Unit)? = null
    private var userRulesJob: Job? = null
    private var totalRuleCount:Long? = null
    private var importSourceSnackbar:Snackbar? = null
    var showUserRules = false
    var userRuleCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.activity_dns_rules, container, false)
    }

    override fun onDestroy() {
        super.onDestroy()
        importSourceSnackbar?.dismiss()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        userRulesJob = launchWithLifecycle {
            userDnsRules = getDatabase().dnsRuleDao().getAllUserRules().toMutableList()
            userRulesJob = null
            launch {
                totalRuleCount = getDatabase().dnsRuleDao().getNonStagedCount()
                updateRuleCountTitle()
            }
        }
        addSource.setOnClickListener {
            NewHostSourceDialog(requireContext(), onSourceCreated = { newSource ->
                if (!sourceAdapterList.contains(newSource)) {
                    val insertPos = sourceAdapterList.indexOfFirst {
                        it.name.compareTo(newSource.name, true) > 0
                    }.let {
                        when (it) {
                            0 -> 0
                            -1 -> sourceAdapterList.size
                            else -> it
                        }
                    }
                    sourceAdapterList.add(insertPos, newSource)
                    val offset = 1 + if(showUserRules) userRuleCount else 0
                    sourceAdapter.notifyItemInserted(offset + insertPos)
                    list.scrollToPosition(insertPos)
                    newSource.id = getDatabase().hostSourceDao().insert(newSource)
                    importSourceSnackbar = Snackbar.make(contentFrame, R.string.window_dnsrules_refresh_sources, Snackbar.LENGTH_INDEFINITE)
                        .setAction(R.string.dialog_hostsourcerefresh_refresh_now) {
                            importSourceSnackbar?.dismiss()
                            requireContext().startService(Intent(requireContext(), RuleImportService::class.java).putExtra("sources", longArrayOf(newSource.id)))
                            refreshProgress.show()
                            refreshProgressShown = true
                        }
                    importSourceSnackbar?.show()
                }
            }, showFileChooser = { callback ->
                fileChosenCallback = callback
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "text/*"
                }, fileChosenRequestCode)
            }).show()
        }
        refresh.setOnClickListener {
            HostSourceRefreshDialog(requireContext(),runRefresh =  {
                if(requireContext().isServiceRunning(RuleImportService::class.java)) {
                    requireContext().startService(Intent(requireContext(), RuleImportService::class.java).putExtra("abort", true))
                } else {
                    requireContext().startService(Intent(requireContext(), RuleImportService::class.java))
                    refreshProgress.show()
                    importSourceSnackbar?.dismiss()
                    refreshProgressShown = true
                }
            }, refreshConfigChanged = {
                getPreferences().apply {
                    val workManager = WorkManager.getInstance(requireContext())
                    workManager.cancelAllWorkByTag("hostSourceRefresh")
                    if(automaticHostRefresh) {
                        val constraints = Constraints.Builder()
                            .setRequiresStorageNotLow(true)
                            .setRequiresBatteryNotLow(true)
                            .setRequiredNetworkType(if (this.automaticHostRefreshWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                            .build()
                        val mappedTimeAmount = automaticHostRefreshTimeAmount.let {
                            if (automaticHostRefreshTimeUnit == HostSourceRefreshDialog.TimeUnit.WEEKS) it * 7
                            else it
                        }.toLong()
                        val mappedTimeUnit = automaticHostRefreshTimeUnit.let {
                            when (it) {
                                HostSourceRefreshDialog.TimeUnit.WEEKS -> TimeUnit.DAYS
                                HostSourceRefreshDialog.TimeUnit.DAYS -> TimeUnit.DAYS
                                HostSourceRefreshDialog.TimeUnit.HOURS -> TimeUnit.HOURS
                                HostSourceRefreshDialog.TimeUnit.MINUTES -> TimeUnit.MINUTES
                            }
                        }
                        val workRequest = PeriodicWorkRequest.Builder(RuleImportStartWorker::class.java,
                            mappedTimeAmount,
                            mappedTimeUnit)
                            .setConstraints(constraints)
                            .setInitialDelay(mappedTimeAmount, mappedTimeUnit)
                            .addTag("hostSourceRefresh")

                        workManager.enqueue(workRequest.build())
                    }
                }
            }).show()
        }
        export.setOnClickListener {
            if (requireContext().isServiceRunning(RuleExportService::class.java)) {
                requireContext().startService(Intent(requireContext(), RuleExportService::class.java).putExtra("abort", true))
            } else {
                ExportDnsRulesDialog(requireContext()) { exportFromSources, exportUserRules, exportType ->
                    fileChosenCallback = {
                        val intent = Intent(requireContext(), RuleExportService::class.java).apply {
                            putExtra(
                                "params",
                                RuleExportService.Params(exportFromSources, exportUserRules, exportType, it.toString())
                            )
                        }
                        requireContext().startService(intent)
                        exportProgress.show()
                        exportProgressShown = true
                    }
                    startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        putExtra(Intent.EXTRA_TITLE, if(exportType == ExportType.NON_WHITELIST) "dnsRuleExport.txt" else "dnsRuleWhitelistExport.txt")
                        type = "text/*"
                    }, fileChosenRequestCode)
                }.show()
            }
        }
        faq.setOnClickListener { requireContext().askOpenFAQ(FAQTopic.DNSRULES) }
        sourceAdapterList = getDatabase().hostSourceDao().getAll().toMutableList()
        sourceRuleCount = sourceAdapterList.map {
            it to (null as Int?)
        }.toMap().toMutableMap()
        adapterDataSource = ListDataSource(sourceAdapterList, listOf(0))
        sourceAdapter = ModelAdapterBuilder.withModelAndViewHolder({ itemView, type ->
            when (type) {
                0 -> SourceViewHolder(itemView, deleteSource = {
                    showInfoTextDialog(requireContext(),
                        getString(R.string.dialog_deletehostsource_title, it.name),
                        getString(R.string.dialog_deletehostsource_message, it.name),
                        getString(R.string.all_yes) to { dialog, _ ->
                            val pos = sourceAdapterList.indexOf(it)
                            if (pos >= 0) {
                                val offset = 1 + if (showUserRules) userRuleCount else 0
                                sourceAdapterList.removeAt(pos)
                                sourceAdapter.notifyItemRemoved(offset + pos)
                            }
                            GlobalScope.launch {
                                getDatabase().dnsRuleDao().deleteAllFromSource(it.id)
                                getDatabase().hostSourceDao().delete(it)
                                notifyRulesChanged()
                            }
                            dialog.dismiss()
                        }, getString(R.string.all_no) to { dialog, _ ->
                            dialog.dismiss()
                        }, null
                    )
                }, changeSourceStatus = { hostSource, enabled ->
                    hostSource.enabled = enabled
                    getDatabase().hostSourceDao().setSourceEnabled(hostSource.id, enabled)
                    notifyRulesChanged()
                }, editSource = { hostSource ->
                    NewHostSourceDialog(requireContext(), onSourceCreated = { newSource ->
                        getDatabase().hostSourceDao().findById(hostSource.id)?.apply {
                            this.name = newSource.name
                            this.source = newSource.source
                            this.whitelistSource = newSource.whitelistSource
                        }?.also { source ->
                            getDatabase().hostSourceDao().update(source)

                            val index = sourceAdapterList.indexOf(hostSource).takeIf { it >= 0 }
                                ?: sourceAdapterList.indexOfFirst { it.id == hostSource.id }
                            sourceAdapterList[index] = source
                            sourceRuleCount[source] = sourceRuleCount[hostSource]
                            sourceRuleCount.remove(hostSource)
                            sourceAdapter.notifyItemChanged(index)
                        }
                    }, showFileChooser = { callback ->
                        fileChosenCallback = callback
                        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            this.type = "text/*"
                        }, fileChosenRequestCode)
                    }, hostSource = hostSource).show()
                }, refreshSource = {
                    importSourceSnackbar?.dismiss()
                    requireContext().startService(
                        Intent(
                            requireContext(),
                            RuleImportService::class.java
                        ).putExtra("sources", longArrayOf(it.id))
                    )
                    refreshProgress.show()
                    refreshProgressShown = true
                })
                1 -> CustomRulesViewHolder(
                    itemView,
                    changeSourceStatus = {
                        getPreferences().customHostsEnabled = it
                    },
                    clearRules = {
                        showInfoTextDialog(requireContext(),
                            getString(R.string.dialog_clearuserrules_title),
                            getString(R.string.dialog_clearuserrules_message),
                            getString(R.string.all_yes) to { dialog, _ ->
                                getDatabase().dnsRuleRepository().deleteAllUserRulesAsync()
                                userDnsRules = mutableListOf()
                                if (showUserRules) {
                                    sourceAdapter.notifyItemRangeRemoved(1, userRuleCount)
                                }
                                userRuleCount = 0
                                notifyRulesChanged()
                                dialog.dismiss()
                            }, getString(R.string.all_no) to { dialog, _ ->
                                dialog.dismiss()
                            }, null
                        )
                    },
                    changeRuleVisibility = {
                        val changeVisibility = {
                            showUserRules = !showUserRules
                            println("SHOW: $showUserRules")
                            if (showUserRules) {
                                userRuleCount = userDnsRules.size
                                adapterDataSource.nonSourcePositions = (0..userRuleCount).toList()
                                if (userRuleCount > 0) activity?.runOnUiThread {
                                    sourceAdapter.notifyItemRangeInserted(1, userRuleCount)
                                }
                            } else {
                                adapterDataSource.nonSourcePositions = listOf(0)
                                if (userRuleCount > 0) activity?.runOnUiThread {
                                    sourceAdapter.notifyItemRangeRemoved(1, userRuleCount)
                                }
                                userRuleCount = 0
                            }
                        }
                        if (userRulesJob == null) changeVisibility()
                        else launchWithLifecycle {
                            userRulesJob?.join()
                            changeVisibility()
                        }
                    },
                    createRule = {
                        DnsRuleDialog(requireContext(), onRuleCreated = { newRule ->
                            val insert = {
                                val insertPos = userDnsRules.indexOfFirst {
                                    it.host > newRule.host
                                }.let {
                                    when (it) {
                                        0 -> 0
                                        -1 -> userDnsRules.size
                                        else -> it
                                    }
                                }
                                val id = if (newRule.isWhitelistRule()) {
                                    getDatabase().dnsRuleDao().insertWhitelist(newRule)
                                    if (userDnsRules.any {
                                            it.host == newRule.host && it.type == newRule.type
                                        }) -1L
                                    else 0L
                                } else getDatabase().dnsRuleDao().insertIgnore(newRule)
                                if (id != -1L) {
                                    userDnsRules.add(insertPos, newRule)
                                    val wereRulesShown = showUserRules
                                    showUserRules = true
                                    if (wereRulesShown) {
                                        userRuleCount += 1
                                        adapterDataSource.nonSourcePositions += (1 + insertPos)
                                        activity?.runOnUiThread {
                                            sourceAdapter.notifyItemInserted(1 + insertPos)
                                        }
                                    } else {
                                        userRuleCount = userDnsRules.size
                                        activity?.runOnUiThread {
                                            adapterDataSource.nonSourcePositions =
                                                (0..userRuleCount).toList()
                                            sourceAdapter.notifyItemChanged(0)
                                            sourceAdapter.notifyItemRangeInserted(1, userRuleCount)
                                            list.smoothScrollToPosition(insertPos)
                                        }
                                    }
                                    notifyRulesChanged()
                                } else {
                                    Snackbar.make(
                                        requireActivity().findViewById(R.id.contentFrame),
                                        R.string.window_dnsrules_hostalreadyexists,
                                        Snackbar.LENGTH_LONG
                                    ).show()
                                }
                            }
                            if (userRulesJob == null) insert()
                            else GlobalScope.launch {
                                userRulesJob?.join()
                                insert()
                            }
                        }).show()
                    })
                else -> CustomRuleHostViewHolder(itemView, deleteRule = {
                    val index = userDnsRules.indexOf(it)
                    userDnsRules.remove(it)
                    getDatabase().dnsRuleRepository().removeAsync(it)
                    userRuleCount -= 1
                    sourceAdapter.notifyItemRemoved(1 + index)
                    if (totalRuleCount != null) {
                        totalRuleCount = totalRuleCount!! - 1
                        updateRuleCountTitle()
                    }
                    notifyRulesChanged()
                }, editRule = {
                    DnsRuleDialog(requireContext(), it) { newRule ->
                        val rows = getDatabase().dnsRuleDao().updateIgnore(newRule)
                        if (rows > 0) {
                            val index = userDnsRules.indexOf(it)
                            userDnsRules[index] = newRule
                            sourceAdapter.notifyItemChanged(1 + index)
                        } else {
                            Snackbar.make(
                                requireActivity().findViewById(android.R.id.content),
                                R.string.window_dnsrules_hostalreadyexists,
                                Snackbar.LENGTH_LONG
                            ).show()
                        }
                    }.show()
                })
            }
        }, adapterDataSource) {
            viewBuilder = { parent, type ->
                layoutInflater.inflate(
                    when (type) {
                        0 -> R.layout.item_datasource
                        1 -> R.layout.item_datasource_rules
                        else -> R.layout.item_dnsrule_host
                    },
                    parent,
                    false
                )
            }
            getItemCount = {
                sourceAdapterList.size + 1 + if(showUserRules) userRuleCount else 0
            }
            bindModelView = { viewHolder, position, data ->
                (viewHolder as SourceViewHolder).display(data)
                when {
                    sourceRuleCount[data] != null -> {
                        val activeCountFormatted = getPreferences().numberFormatter.format(sourceRuleCount[data])
                        val duplicateCountFormatted = getPreferences().numberFormatter.format(data.ruleCount.let {
                            when (it) {
                                null -> 0
                                0 -> 0
                                else -> it - sourceRuleCount[data]!!
                            }
                        })
                        viewHolder.ruleCount.text = getString(R.string.window_dnsrules_customhosts_hostsource_rulecount,
                            activeCountFormatted,
                            duplicateCountFormatted)
                    }
                    data.enabled -> launchWithLifecycle {
                        val prev = sourceRuleCount[data]
                        sourceRuleCount[data] = getDatabase().dnsRuleDao().getCountForHostSource(data.id)
                        if(prev != sourceRuleCount[data]) {
                            val offset = 1 + if(showUserRules) userRuleCount else 0
                            runOnUiThread {
                                sourceAdapter.notifyItemChanged(offset + position)
                            }
                        }
                    }
                    else -> viewHolder.ruleCount.text = getString(R.string.window_dnsrules_customhosts_hostsource_rulecount_pending)
                }
            }
            bindNonModelView = { viewHolder, position ->
                if(viewHolder is CustomRulesViewHolder) {
                    viewHolder.enabled.isChecked = getPreferences().customHostsEnabled
                    if(!viewHolder.elementsShown && showUserRules) {
                        viewHolder.animateCaretSpin()
                        viewHolder.elementsShown = true
                    }
                } else if(viewHolder is CustomRuleHostViewHolder) {
                    viewHolder.display(userDnsRules[position - 1])
                }
            }
            getViewType = { position ->
                when {
                    position == 0 -> 1
                    showUserRules && position <= userRuleCount -> 2
                    else -> 0
                }
            }
            runOnUiThread = {
                activity?.runOnUiThread(it)
            }
        }.build()
        list.layoutManager = LinearLayoutManager(requireContext())
        list.recycledViewPool.setMaxRecycledViews(1, 1)
        list.addItemDecoration(SpaceItemDecorator(requireContext()))
        list.adapter = sourceAdapter
        if(requireContext().isServiceRunning(RuleImportService::class.java)) {
            refreshProgress.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                refreshProgress.show()
                refreshProgressShown = true
            }
        }
        if(requireContext().isServiceRunning(RuleExportService::class.java)) {
            exportProgress.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                exportProgressShown = true
                exportProgress.show()
            }
        }
    }

    private fun notifyRulesChanged() {
        LocalBroadcastManager.getInstance(requireContext()).sendBroadcast(Intent(DnsVpnService.BROADCAST_DNSRULES_REFRESHED))
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if(requestCode == fileChosenRequestCode && resultCode == RESULT_OK){
            if(data?.data != null) fileChosenCallback?.invoke(data.data!!)
            fileChosenCallback = null
        }
    }

    override fun onResume() {
        super.onResume()
        importDoneReceiver = requireContext().registerLocalReceiver(IntentFilter(RuleImportService.BROADCAST_IMPORT_DONE)) {
            refreshProgress.hide()
            refreshProgressShown = false

            launchWithLifecycle {
                sourceRuleCount.keys.forEach {
                    val index = sourceAdapterList.indexOf(it)
                    val offset = 1 + if(showUserRules) userRuleCount else 0
                    if(index >= 0 && (sourceAdapterList[index].enabled || sourceRuleCount[it] != null)) {
                        sourceRuleCount[it] = null
                        launchUi {
                            sourceAdapter.notifyItemChanged(offset +  index)
                        }
                    } else sourceRuleCount[it] = null
                }
                sourceAdapterList = getDatabase().hostSourceDao().getAll().toMutableList()
                totalRuleCount = getDatabase().dnsRuleDao().getNonStagedCount()
                updateRuleCountTitle()
            }
        }
        exportDoneReceiver = requireContext().registerLocalReceiver(IntentFilter(RuleExportService.BROADCAST_EXPORT_DONE)) {
            exportProgress.hide()
            exportProgressShown = false
        }
        if(!requireContext().isServiceRunning(RuleImportService::class.java) && refreshProgressShown) {
            refreshProgress.hide()
            refreshProgressShown = false
        }
        if(!requireContext().isServiceRunning(RuleExportService::class.java) && exportProgressShown) {
            exportProgress.show()
            exportProgressShown = false
        }
    }

    private fun updateRuleCountTitle() {
         activity?.runOnUiThread {
             (activity as AppCompatActivity?)?.supportActionBar?.subtitle = if(getPreferences().dnsRulesEnabled) {
                 val ruleCount = totalRuleCount ?: 0
                 val formattedString = getPreferences().numberFormatter.format(ruleCount)
                 resources.getQuantityString(R.plurals.window_dnsrules_subtitle, ruleCount.toInt(), formattedString)
             } else null
        }
    }

    override fun onPause() {
        super.onPause()
        requireContext().unregisterLocalReceiver(importDoneReceiver)
        requireContext().unregisterLocalReceiver(exportDoneReceiver)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_dnsrule, menu)
        val switch =  menu.findItem(R.id.rulesEnabled)?.actionView?.findViewById<Switch>(R.id.actionbarSwitch)
        val search = menu.findItem(R.id.search)
        switch?.isChecked = getPreferences().dnsRulesEnabled.also {
            overlay.visibility = if(it) View.GONE else View.VISIBLE
            search.isEnabled = it
        }
        switch?.setOnCheckedChangeListener { _, isChecked ->
            getPreferences().dnsRulesEnabled = isChecked
            overlay.visibility = if(isChecked) View.GONE else View.VISIBLE
            search.isEnabled = isChecked
            updateRuleCountTitle()
        }
        search?.setOnMenuItemClickListener {
            DnsRuleSearchDialog(requireContext()).show()
            true
        }
    }

    private class SourceViewHolder(
        view: View,
        deleteSource: (HostSource) -> Unit,
        changeSourceStatus: (HostSource, enabled: Boolean) -> Unit,
        editSource: (HostSource) -> Unit,
        refreshSource:(HostSource) -> Unit
    ) : BaseViewHolder(view) {
        val text: TextView = view.text
        val subText: TextView = view.subText
        val enabled: Switch = view.enable
        val delete: ImageButton = view.delete
        val ruleCount: TextView = view.ruleCount
        val refresh: ImageButton = view.refresh
        val whitelistIndicator: ImageView = view.sourceWhitelistIndicator
        private var source: HostSource? = null

        init {
            delete.setOnClickListener {
                source?.also(deleteSource)
            }
            enabled.setOnCheckedChangeListener { _, isChecked ->
                source?.also {
                    changeSourceStatus(it, isChecked)
                }
                refresh.isEnabled = isChecked
            }
            refresh.setOnClickListener {
                source?.also(refreshSource)
            }
            view.cardContent.setOnClickListener {
                source?.also(editSource)
            }
        }

        fun display(source: HostSource) {
            this.source = source
            text.text = source.name
            enabled.isChecked = source.enabled
            subText.text = source.source
            whitelistIndicator.visibility = if(source.whitelistSource) View.VISIBLE else View.GONE
            refresh.isEnabled = source.enabled
        }

        override fun destroy() {}
    }

    private class CustomRulesViewHolder(view: View,
                                        changeSourceStatus: (Boolean) -> Unit,
                                        clearRules: () -> Unit,
                                        changeRuleVisibility:(showRules:Boolean) -> Unit,
                                        createRule:() -> Unit) :
        BaseViewHolder(view) {
        val clear: ImageButton = view.clear
        val enabled: Switch = view.enable
        val openList: ImageButton = view.openList
        val add: ImageButton = view.add
        var elementsShown = false

        init {
            enabled.setOnCheckedChangeListener { _, isChecked ->
                changeSourceStatus(isChecked)
            }
            clear.setOnClickListener {
                clearRules()
            }
            openList.setOnClickListener {
                animateCaretSpin()
                changeRuleVisibility(!elementsShown)
                elementsShown = !elementsShown
            }
            view.cardContent.setOnClickListener {
                enabled.isChecked = !enabled.isChecked
            }
            add.setOnClickListener {
                createRule()
            }
        }

        fun animateCaretSpin(){
            if(elementsShown) {
                openList.animate().rotationBy(-90f).setDuration(350).start()
            } else {
                openList.animate().rotationBy(90f).setDuration(350).start()
            }
        }

        override fun destroy() {}
    }

    private class CustomRuleHostViewHolder(view:View,
                                           deleteRule:(DnsRule) -> Unit,
                                           editRule:(DnsRule) -> Unit):BaseViewHolder(view) {
        val text: TextView = view.text
        val delete: ImageButton = view.delete
        val cardContent: RelativeLayout = view.cardContent
        val whitelistIndicator: ImageView = view.whitelistIndicator
        lateinit var dnsRule:DnsRule

        init {
            delete.setOnClickListener {
                deleteRule(dnsRule)
            }
            cardContent.setOnClickListener {
                editRule(dnsRule)
            }
        }

        fun display(rule:DnsRule) {
            dnsRule = rule
            text.text = DnsRuleDialog.printableHost(rule.host)
            whitelistIndicator.visibility = if(rule.isWhitelistRule()) View.VISIBLE else View.GONE
        }
        override fun destroy() {}
    }

    companion object {
        const val latestSourcesVersion = 4
        private val defaultHostSources:Map<Int, List<HostSource>> by lazy(LazyThreadSafetyMode.NONE) {
            mutableMapOf<Int, List<HostSource>>().apply {
                put(1, mutableListOf(
                    HostSource("Energized Basic", "https://block.energized.pro/basic/formats/domains.txt"),
                    HostSource("Energized Blu", "https://block.energized.pro/blu/formats/domains.txt"),
                    HostSource("Energized Spark", "https://block.energized.pro/spark/formats/domains.txt"),
                    HostSource("Energized Porn", "https://block.energized.pro/porn/formats/domains.txt"),
                    HostSource("Energized Ultimate", "https://block.energized.pro/ultimate/formats/domains.txt"),
                    HostSource("AdAway", "https://adaway.org/hosts.txt"),
                    HostSource("StevenBlack unified", "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"),
                    HostSource("CoinBlockerList", "https://zerodot1.gitlab.io/CoinBlockerLists/hosts"),
                    HostSource("Malewaredomainlist.com", "https://www.malwaredomainlist.com/hostslist/hosts.txt"),
                    HostSource("PiHoleBlocklist Android tracking", "https://raw.githubusercontent.com/Perflyst/PiHoleBlocklist/master/android-tracking.txt"),
                    HostSource("Quidsup NoTrack Tracker Blocklist", "https://gitlab.com/quidsup/notrack-blocklists/raw/master/notrack-blocklist.txt"),
                    HostSource("someonewhocares.org", "https://someonewhocares.org/hosts/zero/hosts")
                ).onEach { it.enabled = false })
                put(2, mutableListOf(
                    HostSource("Energized unblock", "https://raw.githubusercontent.com/EnergizedProtection/unblock/master/basic/formats/domains.txt", true),
                    HostSource("hblock", "https://hblock.molinero.dev/hosts")
                ).onEach { it.enabled = false })
                put(4, mutableListOf(
                    HostSource("Energized Unified", "https://block.energized.pro/unified/formats/domains.txt", false).apply {
                        enabled = false
                    }
                ))
            }
        }
        private val updatedHostSources:Map<Int, List<HostSource>> by lazy {
            mutableMapOf<Int, List<HostSource>>().apply {
                put(3, (defaultHostSources[1] ?: error("")).subList(0, 4))
                put(4, mutableListOf(defaultHostSources.getValue(1)[4]))
            }
        }

        fun getDefaultHostSources(versionStart:Int):List<HostSource> {
            return getDefaultHostSources(versionStart..Integer.MAX_VALUE)
        }

        fun getUpdatedHostSources(versionStart:Int):List<HostSource> {
            return getUpdatedHostSources(versionStart..Integer.MAX_VALUE)
        }

        private fun getDefaultHostSources(versionRange:IntRange): List<HostSource> {
            if(versionRange.first > latestSourcesVersion) return emptyList()
            return defaultHostSources.filter {
                it.key in versionRange
            }.values.flatten()
        }

        private fun getUpdatedHostSources(versionRange: IntRange):List<HostSource> {
            if(versionRange.first > latestSourcesVersion) return emptyList()
            return updatedHostSources.filter {
                it.key in versionRange
            }.values.flatten()
        }
    }
}