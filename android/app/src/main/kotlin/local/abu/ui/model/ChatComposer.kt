package local.abu.ui.model

internal fun sendableChatText(value: String, busy: Boolean): String? =
    value.takeIf { !busy && it.isNotBlank() && it.codePointCount(0, it.length) <= 4000 }
