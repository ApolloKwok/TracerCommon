package pers.apollokwok.tracer.common.util

internal inline fun <T> Iterable<T>.filterOutRepeated(condition: (T)->Any? = { it }): List<T> =
    groupBy(condition)
    .values
    .filter { it.size > 1 }
    .flatten()