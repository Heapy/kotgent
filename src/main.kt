package io.kotgent

const val VERSION = "0.1.0-SNAPSHOT"

fun versionLine(): String = "kotgent $VERSION"

fun main() {
    println(versionLine())
}
