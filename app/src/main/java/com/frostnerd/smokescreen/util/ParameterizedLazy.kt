package com.frostnerd.smokescreen.util

import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

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

class ParameterizedLazy<ReturnType:Any, ParamType:Any>(creator:(param:ParamType) -> ReturnType):ReadOnlyProperty<Any?, (ParamType) -> ReturnType> {
    private lateinit var param:ParamType
    private val actualValue:ReturnType by lazy {
        creator(param)
    }

    override fun getValue(thisRef: Any?, property: KProperty<*>): (ParamType) -> ReturnType {
        return {
            param = it
            actualValue
        }
    }
}
class ParameterizedLazyWithLazyParam<ReturnType:Any, ParamType:Any>(creator:(param:ParamType) -> ReturnType):ReadOnlyProperty<Any?, (() -> ParamType) -> ReturnType> {
    private lateinit var param:() -> ParamType
    private val actualValue:ReturnType by lazy {
        creator(param())
    }

    override fun getValue(thisRef: Any?, property: KProperty<*>): (() -> ParamType) -> ReturnType {
        return {
            param = it
            actualValue
        }
    }
}

fun <ReturnType:Any, ParamType:Any> parameterizedLazy(creator:(param:ParamType) -> ReturnType) = ParameterizedLazy(creator)
@Suppress("unused")
fun <ReturnType:Any, ParamType:Any> parameterizedLazyWithLazyParam(creator:(param:() -> ParamType) -> ReturnType) = ParameterizedLazyWithLazyParam(creator)