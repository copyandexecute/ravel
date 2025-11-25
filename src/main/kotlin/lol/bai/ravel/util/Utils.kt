package lol.bai.ravel.util

fun String.capitalizeFirstChar() = replaceFirstChar { it.uppercase() }
fun String.decapitalizeFirstChar() = replaceFirstChar { it.lowercase() }

fun wtf(): Nothing = throw UnsupportedOperationException()

@Suppress("unused")
fun <T> mock(): T = throw UnsupportedOperationException()
