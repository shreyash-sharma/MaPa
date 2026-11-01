package org.pytorch.executorch
class Tensor private constructor(private val d: FloatArray) {
    val dataAsFloatArray: FloatArray get() = d
    companion object { fun fromBlob(data: FloatArray, shape: LongArray): Tensor = Tensor(data) }
}
class EValue private constructor(private val t: Tensor) {
    fun toTensor(): Tensor = t
    companion object { fun from(t: Tensor): EValue = EValue(t) }
}
class Module : AutoCloseable {
    fun forward(vararg inputs: EValue): Array<EValue> = inputs.map { it }.toTypedArray()
    override fun close() {}
    companion object { fun load(path: String): Module = Module() }
}
