package example

import org.junit.jupiter.api.Test as Spec

annotation class Marker

annotation class OpenForGraph

open class Base

sealed interface Outcome {
    data class Value(val value: String) : Outcome
}

interface Service {
    fun execute(value: String): String
}

@Marker
@OpenForGraph
class ServiceImpl(val state: String = "ready") : Base(), Service {
    constructor(value: Int) : this(value.toString())

    var mutable: String = state
        get() = field
        set(value) {
            field = value
        }

    val delegated: String by lazy { state }

    override fun execute(value: String): String = value.uppercase()

    fun readMutable(): String = mutable
}

@Deprecated("diagnostic fixture")
fun old(): Unit = Unit

fun String.describe(): String = "string:$this"

fun Int.describe(): String = "integer:$this"

fun <T> identity(value: T): T = value

suspend fun suspended(value: String): String = value

inline fun <T> inlined(value: T, block: (T) -> Unit): T {
    block(value)
    return value
}

fun overloaded(value: String): String = value

fun overloaded(value: Int): Int = value

fun runFixture(service: Service): String {
    old()
    val created: Service = ServiceImpl(1)
    val first = "value".describe()
    val second = 7.describe()
    return service.execute("${created.execute(first)}/$second")
}

@Spec
fun testFixture(): Unit = Unit
