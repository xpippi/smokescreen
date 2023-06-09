package com.frostnerd.smokescreen.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

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
@Entity(tableName = "HostSource")
data class HostSource(
    var name: String,
    var source: String,
    var whitelistSource:Boolean = false
) {
    val isFileSource: Boolean
        get() {
            return !source.startsWith("http", false)
        }
    @PrimaryKey(autoGenerate = true) var id: Long = 0
    var enabled: Boolean = true
    var ruleCount:Int? = null

    // Content of the ETag header for supported rule sources
    // Null if file-based or unknown (e.g. not requested yet)
    var checksum:String? = null
}