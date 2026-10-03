package chat.hunmeng.console

const val BOT_FATHER_URL = "https://t.me/BotFather"

/** Injectable actions allow failure handling to be tested without a real clipboard or app. */
fun tryCopyText(text: String, write: (String) -> Unit): Boolean = try {
    write(text)
    true
} catch (_: Exception) {
    false
}

fun reportCopyFallback(report: String, write: (String) -> Unit): String? =
    if (tryCopyText(report, write)) null else report

fun tryOpenLink(url: String, open: (String) -> Unit): Boolean = try {
    open(url)
    true
} catch (_: Exception) {
    false
}
