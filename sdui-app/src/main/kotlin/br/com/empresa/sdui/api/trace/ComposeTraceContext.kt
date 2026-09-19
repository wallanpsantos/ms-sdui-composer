package br.com.empresa.sdui.api.trace

class ComposeTraceContext {
    private val values = ThreadLocal<Map<String, String>>()

    fun open(context: Map<String, String>) {
        values.set(context)
    }

    fun get(): Map<String, String> = values.get().orEmpty()

    fun close() {
        values.remove()
    }
}
