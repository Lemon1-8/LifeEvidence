package app.shenghuoyouju.search

/** Exact cased BERT BasicTokenizer + WordPiece for the pinned BGE Chinese vocabulary. */
data class BertEncoding(val inputIds: LongArray, val attentionMask: LongArray, val tokenTypeIds: LongArray)

class BertTokenizer(vocab: List<String>) {
    private val vocabulary = vocab.mapIndexed { index, token -> token to index.toLong() }.toMap()
    private val unknown = requireNotNull(vocabulary["[UNK]"])
    private val cls = requireNotNull(vocabulary["[CLS]"])
    private val sep = requireNotNull(vocabulary["[SEP]"])
    private val specials = Regex("\\[UNK]|\\[SEP]|\\[PAD]|\\[CLS]|\\[MASK]")

    fun encode(text: String, maxLength: Int = 350): BertEncoding {
        require(maxLength >= 2)
        val ids = mutableListOf(cls)
        var cursor = 0
        for (special in specials.findAll(text)) {
            ids.addAll(tokenize(text.substring(cursor, special.range.first)))
            ids.add(requireNotNull(vocabulary[special.value]))
            cursor = special.range.last + 1
        }
        ids.addAll(tokenize(text.substring(cursor)))
        val truncated = ids.take(maxLength - 1) + sep
        return BertEncoding(truncated.toLongArray(), LongArray(truncated.size) { 1L }, LongArray(truncated.size))
    }

    private fun tokenize(text: String): List<Long> {
        val clean = StringBuilder()
        var offset = 0
        while (offset < text.length) {
            val code = Character.codePointAt(text, offset)
            offset += Character.charCount(code)
            when {
                code == 0 || code == 0xfffd || isControl(code) -> Unit
                isWhitespace(code) -> clean.append(' ')
                isChinese(code) || isPunctuation(code) -> clean.append(' ').appendCodePoint(code).append(' ')
                else -> clean.appendCodePoint(code)
            }
        }
        return clean.toString().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.flatMap(::wordPiece)
    }

    private fun wordPiece(token: String): List<Long> {
        if (token.codePointCount(0, token.length) > 100) return listOf(unknown)
        val result = mutableListOf<Long>()
        var start = 0
        while (start < token.length) {
            var end = token.length
            var match: Long? = null
            while (start < end) {
                val piece = (if (start > 0) "##" else "") + token.substring(start, end)
                match = vocabulary[piece]
                if (match != null) break
                end = token.offsetByCodePoints(end, -1)
            }
            if (match == null) return listOf(unknown)
            result.add(match)
            start = end
        }
        return result
    }

    private fun isWhitespace(code: Int) = code == 32 || code == 9 || code == 10 || code == 13 ||
        Character.getType(code) == Character.SPACE_SEPARATOR.toInt()

    private fun isControl(code: Int): Boolean {
        if (code == 9 || code == 10 || code == 13) return false
        return Character.getType(code) in setOf(Character.CONTROL.toInt(), Character.FORMAT.toInt(),
            Character.PRIVATE_USE.toInt(), Character.SURROGATE.toInt(), Character.UNASSIGNED.toInt())
    }

    private fun isPunctuation(code: Int): Boolean {
        if (code in 33..47 || code in 58..64 || code in 91..96 || code in 123..126) return true
        return Character.getType(code) in setOf(Character.CONNECTOR_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(), Character.END_PUNCTUATION.toInt(), Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
            Character.FINAL_QUOTE_PUNCTUATION.toInt(), Character.OTHER_PUNCTUATION.toInt())
    }

    private fun isChinese(code: Int) = code in 0x4e00..0x9fff || code in 0x3400..0x4dbf ||
        code in 0x20000..0x2a6df || code in 0x2a700..0x2b73f || code in 0x2b740..0x2b81f ||
        code in 0x2b820..0x2ceaf || code in 0xf900..0xfaff || code in 0x2f800..0x2fa1f
}
