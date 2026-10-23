package com.nya.dstranslate.data

/**
 * Разбиение живой речи на предложения.
 *
 * Нужно для дозаписи в режиме «Текст»: когда пользователь дописывает текст,
 * переводится только хвост, а не весь текст заново. Хвост режется по границам
 * предложений, чтобы не отправлять в модель обрывок вроде «и потом мы по»,
 * и чтобы перевод склеивался с уже готовым.
 */
object TextSplit {

    private fun isTerminator(c: Char): Boolean = c in ".!?。！？…\n"

    private fun isHan(c: Char): Boolean = c.code in 0x4E00..0x9FFF

    /** Есть ли в тексте хотя бы один знак конца предложения. */
    fun hasTerminator(text: String): Boolean = text.any { isTerminator(it) }

    /**
     * Заканчивается ли текст концом предложения. Нужно, чтобы понять, где
     * пользователь дописал: после «Привет.» дописанное — новое предложение,
     * после «Привет, как» дописанное — продолжение того же самого.
     */
    fun endsWithTerminator(text: String): Boolean {
        val last = text.lastOrNull { !it.isWhitespace() } ?: return false
        return isTerminator(last)
    }

    /**
     * Список предложений. Знаки конца предложения остаются в конце своего
     * предложения.
     *
     * Точка — не всегда конец предложения, поэтому границей она считается не
     * всегда, а с оговорками: «3.5» (число), «т.е.» (сокращение), «...»
     * (многоточие), «т.е. так» (после сокращения идёт строчная буква).
     * Строчная буква после точки — главный признак продолжения: предложения
     * в русском начинаются с заглавной.
     */
    fun split(text: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()

        for ((index, c) in text.withIndex()) {
            current.append(c)
            if (!isTerminator(c)) continue

            val next = text.getOrNull(index + 1)
            val prev = text.getOrNull(index - 1)
            // Первый непробельный символ после знака — для проверки регистра.
            val after = text.asSequence().drop(index + 1).firstOrNull { !it.isWhitespace() }

            val boundary = when {
                c == '\n' -> true
                next == null -> true
                isHan(next) -> true

                c == '.' || c == '…' -> when {
                    // Многоточие «...»: режем на последней точке.
                    next == '.' -> false
                    // Сокращения «т.е.», «т.д.»: после точки буква без пробела.
                    next.isLetter() -> false
                    // «3.5» — не граница предложения.
                    next.isDigit() || prev?.isDigit() == true -> false
                    // «т.е. так»: после пробела продолжаем со строчной — это не новое предложение.
                    after != null && after.isLowerCase() -> false
                    else -> true
                }

                next.isWhitespace() -> true
                else -> true
            }
            if (boundary) {
                current.toString().trim().takeIf { it.isNotEmpty() }?.let { result += it }
                current.setLength(0)
            }
        }
        current.toString().trim().takeIf { it.isNotEmpty() }?.let { result += it }
        return result
    }

    /**
     * Последние [max] предложений текста. Если предложений не больше — текст
     * возвращается как есть, чтобы не потерять начало недописанной фразы.
     */
    fun lastSentences(text: String, max: Int): String {
        if (max <= 0) return text
        val parts = split(text)
        if (parts.size <= max) return text
        val joiner = if (text.any { isHan(it) }) "" else " "
        return parts.takeLast(max).joinToString(joiner)
    }
}
