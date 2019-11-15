package com.frostnerd.smokescreen.util.proxy

import android.content.Context
import com.frostnerd.dnstunnelproxy.LocalResolver
import com.frostnerd.smokescreen.database.getDatabase
import com.frostnerd.smokescreen.dialog.DnsRuleDialog
import com.frostnerd.smokescreen.getPreferences
import com.frostnerd.smokescreen.util.MaxSizeMap
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.minidns.dnsmessage.Question
import org.minidns.record.A
import org.minidns.record.AAAA
import org.minidns.record.Record
import java.util.*
import kotlin.collections.HashSet
import kotlin.math.abs

class DnsRuleResolver(context: Context) : LocalResolver(true) {
    private val maxWhitelistCacheSize = 250
    private val maxResolvedCacheSize = 500
    private val maxWildcardResolvedCacheSize = 250

    private val dao = context.getDatabase().dnsRuleDao()
    private val resolveResults = mutableMapOf<Int, String>()
    private val wwwRegex = Regex("^www\\.")
    private val useUserRules = context.getPreferences().customHostsEnabled
    private var ruleCount: Int? = null
    private var wildcardCount: Int? = null
    private var whitelistCount: Int? = null
    private var nonWildcardCount:Int? = null
    private var wildcardWhitelistCount:Int? = null
    private var nonWildcardWhitelistCount:Int? = null

    // These sets contain hashes of the hosts, the most significant bit of the hash it 1 for IPv6 and 0 for IPv4
    // Hashes are stored because they are shorter than strings (Int=4 Bytes, String=2-3 per char)
    private var cachedWhitelisted = HashSet<Int>(15)
    private var cachedResolved = MaxSizeMap<Int, String>(maxResolvedCacheSize, 40)
    private var cachedWildcardResolved = MaxSizeMap<Int, String>(maxWildcardResolvedCacheSize, 30)

    private var previousRefreshJob:Job? = null

    init {
        refreshRuleCount()
    }

    fun refreshRuleCount() {
        previousRefreshJob?.cancel()
        previousRefreshJob = GlobalScope.launch {
            val previousRuleCount = ruleCount
            ruleCount = dao.getCount().toInt()
            wildcardCount = dao.getWildcardCount().toInt()
            val previousWhitelistCount = whitelistCount
            whitelistCount = dao.getWhitelistCount().toInt()
            nonWildcardCount = ruleCount!! - wildcardCount!!

            wildcardWhitelistCount = dao.getWildcardWhitelistCount().toInt()
            nonWildcardWhitelistCount = whitelistCount!! - wildcardWhitelistCount!!

            if(previousWhitelistCount != whitelistCount) cachedWhitelisted.clear()
            if(previousRuleCount != ruleCount){
                cachedResolved.clear()
                cachedWildcardResolved.clear()
            }
        }
    }

    override suspend fun canResolve(question: Question): Boolean {
        return if ((ruleCount == 0 || (ruleCount != null && ruleCount == whitelistCount)) || (question.type != Record.TYPE.A && question.type != Record.TYPE.AAAA)) {
            false
        } else {
            val uniformQuestion = question.name.toString().replace(wwwRegex, "").toLowerCase(Locale.ROOT)
            val isWhitelisted = if (whitelistCount != 0) {
                if (whitelistCount == cachedWhitelisted.size) {
                    cachedWhitelisted.contains(hashHost(uniformQuestion, question.type))
                } else {
                    (cachedWhitelisted.size != 0 && cachedWhitelisted.contains(
                        hashHost(
                            uniformQuestion,
                            question.type
                        )
                    )) || (wildcardWhitelistCount != 0 && dao.findPossibleWildcardRuleTarget(
                        uniformQuestion,
                        question.type,
                        useUserRules,
                        true,
                        false
                    ).any {
                        DnsRuleDialog.databaseHostToMatcher(it.host).reset(uniformQuestion)
                            .matches()
                    }) || (nonWildcardWhitelistCount != 0 && dao.findNonWildcardWhitelistEntry(
                        uniformQuestion,
                        useUserRules
                    ).isNotEmpty())
                }
            } else false

            if (isWhitelisted) {
                if(cachedWhitelisted.size >= maxWhitelistCacheSize) cachedWhitelisted.clear()
                cachedWhitelisted.add(hashHost(uniformQuestion, question.type))
                false
            }
            else {
                val resolveResult = if(nonWildcardCount != 0) {
                    if(nonWildcardCount == cachedResolved.size) {
                        cachedResolved[hashHost(uniformQuestion, question.type)]
                    } else {
                        cachedResolved[hashHost(uniformQuestion, question.type)] ?: dao.findRuleTarget(uniformQuestion, question.type, useUserRules)
                            ?.let {
                                when (it) {
                                    "0" -> "0.0.0.0"
                                    "1" -> {
                                        if (question.type == Record.TYPE.AAAA) "::1"
                                        else "127.0.0.1"
                                    }
                                    else -> it
                                }
                            }
                    }
                } else null
                if (resolveResult != null) {
                    resolveResults[question.hashCode()] = resolveResult
                    true
                } else if (wildcardCount != 0) {
                    val wildcardResolveResult = cachedWildcardResolved[hashHost(uniformQuestion, question.type)] ?: dao.findPossibleWildcardRuleTarget(
                        uniformQuestion,
                        question.type,
                        useUserRules,
                        false,
                        true
                    ).firstOrNull {
                        DnsRuleDialog.databaseHostToMatcher(it.host)
                            .reset(uniformQuestion).matches()
                    }?.let {
                        if (question.type == Record.TYPE.AAAA) it.ipv6Target
                            ?: it.target
                        else it.target
                    }?.let {
                        when (it) {
                            "0" -> "0.0.0.0"
                            "1" -> {
                                if (question.type == Record.TYPE.AAAA) "::1"
                                else "127.0.0.1"
                            }
                            else -> it
                        }
                    }
                    if (wildcardResolveResult != null) {
                        cachedWildcardResolved[hashHost(uniformQuestion, question.type)] = wildcardResolveResult
                        resolveResults[question.hashCode()] = wildcardResolveResult
                        true
                    } else {
                        false
                    }
                } else {
                    false
                }
            }
        }
    }

    // A fast hashing function with high(er) collision rate
    // As only a few hosts are stored at the same time the collision rate is not important.
    // The effective room is 2^31
    private fun hashHost(host: String, type: Record.TYPE): Int {
        return if (type == Record.TYPE.AAAA) {
            abs(host.hashCode()) * -1 // Sets first bit to 1
        } else {
            abs(host.hashCode()) // Sets first bit to 0
        }
    }

    override suspend fun resolve(question: Question): List<Record<*>> {
        val result = resolveResults.remove(question.hashCode())
        return result?.let {
            val data = if (question.type == Record.TYPE.A) {
                A(it)
            } else {
                AAAA(it)
            }
            listOf(
                Record(
                    question.name.toString(),
                    question.type,
                    question.clazz.value,
                    9999,
                    data
                )
            )
        } ?: throw IllegalStateException()
    }

    override fun cleanup() {}

}