package app.shenghuoyouju.data

/** Git smart HTTP v0 ref discovery; packet lengths count bytes, not Unicode characters. */
object GitHead {
    const val URL = "https://github.com/eternity4719/HowToLiveBetter.git/info/refs?service=git-upload-pack"
    const val TRANSPORT = "git-smart-http-info-refs"

    fun parse(bytes: ByteArray): String {
        var position = 0
        fun packet(): String? {
            require(bytes.size - position >= 4) { "Git 引用响应被截断" }
            val prefix = String(bytes, position, 4, Charsets.US_ASCII)
            require(prefix.all { it in "0123456789abcdefABCDEF" }) { "Git 引用包长度无效" }
            val length = prefix.toInt(16)
            position += 4
            if (length == 0) return null
            require(length >= 4 && length - 4 <= bytes.size - position) { "Git 引用响应被截断" }
            return String(bytes, position, length - 4, Charsets.UTF_8).also { position += length - 4 }
        }

        require(packet()?.removeSuffix("\n") == "# service=git-upload-pack") { "Git 引用服务无效" }
        require(packet() == null) { "Git 引用响应缺少分隔标识" }
        var main: String? = null
        while (true) {
            val record = packet()?.substringBefore('\u0000')?.removeSuffix("\n") ?: break
            require(record.length > 41 && record[40] == ' ') { "Git 引用格式无效" }
            val sha = record.substring(0, 40)
            require(sha.matches(Regex("[a-f0-9]{40}"))) { "上游版本标识无效" }
            if (record.substring(41) == "refs/heads/main") {
                require(main == null) { "Git 主分支引用重复" }
                main = sha
            }
        }
        require(position == bytes.size) { "Git 引用响应包含多余数据" }
        return requireNotNull(main) { "未找到上游 main 分支" }
    }
}
