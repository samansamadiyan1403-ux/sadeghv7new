package ir.sadegh.assistant

sealed class Cmd {
    data class Call(val targets: List<String>) : Cmd()
    data class Open(val targets: List<String>) : Cmd()
    object Time : Cmd()
    object Unknown : Cmd()
}

object Parser {
    private enum class K { CALL, OPEN, TIME, NONE }

    private val callW = setOf("call", "calling", "dial", "کال", "کل", "کول", "تماس", "زنگ", "تلفن", "فون")
    private val openW = setOf("open", "launch", "run", "start", "اوپن", "باز", "بازکن", "اجرا", "استارت")
    private val stopW = setOf(
        "با", "به", "رو", "را", "بزن", "بزنی", "بگیر", "بگیری", "کن", "بکن", "کنی", "کنید", "لطفا", "برای",
        "برام", "من", "منو", "میشه", "میخوام", "بخوام", "یه", "یک", "برنامه", "اپلیکیشن", "اپ", "نرم", "افزار",
        "شماره", "مخاطب", "please", "to", "the", "with", "my", "for", "me", "app", "application", "contact", "number"
    )

    private fun one(text: String): Pair<K, String> {
        val words = text.split(" ").filter { it.isNotBlank() && !Text.isWake(it) }
        val kind = when {
            words.any { it in callW } -> K.CALL
            words.any { it in openW } -> K.OPEN
            words.any { it == "ساعت" || it == "time" } -> K.TIME
            else -> K.NONE
        }
        val target = words.filter { it !in callW && it !in openW && it !in stopW }.joinToString(" ")
        return kind to target
    }

    fun parse(cands: List<String>): Cmd {
        val parsed = cands.map { one(it) }
        val kind = parsed.firstOrNull { it.first != K.NONE }?.first ?: return Cmd.Unknown
        if (kind == K.TIME) return Cmd.Time
        // گزینه‌های بدون فعل هم به‌عنوان نام هدف در نظر گرفته می‌شوند (گاهی «کال» حذف می‌شود)
        val targets = parsed.filter { it.first == kind || it.first == K.NONE }
            .map { it.second }.filter { it.isNotBlank() }.distinct()
        return if (kind == K.CALL) Cmd.Call(targets) else Cmd.Open(targets)
    }
}
