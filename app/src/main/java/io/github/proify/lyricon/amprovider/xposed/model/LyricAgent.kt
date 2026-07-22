/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

@file:Suppress("unused")

package io.github.proify.lyricon.amprovider.xposed.model

import kotlinx.serialization.Serializable

@Serializable
data class LyricAgent(
    var nameTypes: IntArray = intArrayOf(),
    var nameTypeNames: Array<String> = arrayOf(),
    var type: Long = 0,
    var typeName: String? = null,
    var id: String? = null
) {

    companion object {
        fun getNameTypesNames(nameTypes: IntArray): Array<String> {
            val nameTypesName = mutableListOf<String>()
            nameTypes.forEach {
                nameTypesName.add(getNameTypeName(it)?.typeName ?: "")
            }
            return nameTypesName.toTypedArray()
        }

        fun getNameTypeName(nameType: Int): NameType? {
            return NameType.entries.firstOrNull { it.type == nameType }
        }

        fun getType(type: Long): Type? {
            return Type.entries.firstOrNull { it.type == type }
        }
    }

    enum class NameType(var typeName: String, val type: Int) {
        NONE("None", 0),
        FULL("Full", 1),
        FAMILY("Family", 2),
        GIVEN("Given", 3),
        ALIAS("Alias", 4),
        OTHER("Other", 5)
    }

    enum class Type(val typeName: String, val type: Long) {
        NONE("None", 0),
        PERSON("Person", 1),
        CHARACTER("Character", 2),
        GROUP("Group", 3),
        ORGANIZATION("Organization", 4),
        OTHER("Other", 5)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LyricAgent) return false

        if (type != other.type) return false
        if (!nameTypes.contentEquals(other.nameTypes)) return false
        if (!nameTypeNames.contentEquals(other.nameTypeNames)) return false
        if (typeName != other.typeName) return false
        if (id != other.id) return false

        return true
    }

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + nameTypes.contentHashCode()
        result = 31 * result + nameTypeNames.contentHashCode()
        result = 31 * result + (typeName?.hashCode() ?: 0)
        result = 31 * result + (id?.hashCode() ?: 0)
        return result
    }
}