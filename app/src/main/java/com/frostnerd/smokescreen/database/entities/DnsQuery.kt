package com.frostnerd.smokescreen.database.entities

import android.util.Base64
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.frostnerd.dnstunnelproxy.QueryListener
import com.frostnerd.smokescreen.database.converters.DnsTypeConverter
import com.frostnerd.smokescreen.database.converters.QuerySourceConverter
import com.frostnerd.smokescreen.database.converters.StringListConverter
import com.frostnerd.smokescreen.database.recordFromBase64
import com.frostnerd.smokescreen.equalsAny
import org.minidns.record.A
import org.minidns.record.AAAA
import org.minidns.record.Record

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
@Entity(tableName = "DnsQuery")
@TypeConverters(DnsTypeConverter::class, StringListConverter::class, QuerySourceConverter::class)
data class DnsQuery(
    @PrimaryKey(autoGenerate = true) var id: Long = 0,
    val type: Record.TYPE,
    val name: String,
    var askedServer: String?,
    var responseSource:QueryListener.Source,
    val questionTime: Long,
    var responseTime: Long = 0,
    var responses: List<String>,
    var isHostBlockedByDnsServer:Boolean = responsesBlockHost(responses)
) {
    @delegate:Ignore
    val shortName:String by lazy { calculateShortName() }
    @delegate:Ignore
    val parsedResponses:List<Record<*>> by lazy {
        parseResponses(responses)
    }

    companion object {
        private val SHORT_DOMAIN_REGEX = "^((?:[^.]{1,3}\\.)+)([\\w]{4,})(?:\\.(?:[^.]*))*\$".toRegex()

        private fun responsesBlockHost(responses:List<String>):Boolean {
            return parseResponses(responses).any {
                (it.type == Record.TYPE.A && (it.payload as A).toString() == "0.0.0.0") ||
                        (it.type == Record.TYPE.AAAA && (it.payload as AAAA).toString().equalsAny("::0", "0:0:0:0:0:0:0:0"))
            }
        }

        private fun parseResponses(responses:List<String>): List<Record<*>> {
            if(responses.isEmpty()) return emptyList()

            val parsedResponses = mutableListOf<Record<*>>()
            for (response in responses) {
                parsedResponses.add(recordFromBase64(response))
            }
            return parsedResponses
        }

        fun encodeResponse(record: Record<*>):String = Base64.encodeToString(record.toByteArray(), Base64.NO_WRAP)
    }

    /**
     * Returns a shortened representation of the name.
     * E.g frostnerd.com for ads.frostnerd.com, or "dgfhd" for "dgfhd"
     */
    private fun calculateShortName():String {
        val match = SHORT_DOMAIN_REGEX.matchEntire(name.reversed())
        if(match != null) {
            if(match.groups.size == 3) {
                return match.groupValues[2].reversed() + match.groupValues[1].reversed()
            }
            return name
        } else return name
    }

    override fun toString(): String {
        return "DnsQuery(id=$id, type=$type, name='$name', askedServer=$askedServer, questionTime=$questionTime, responseTime=$responseTime, responses={${responses.size}})"
    }
}