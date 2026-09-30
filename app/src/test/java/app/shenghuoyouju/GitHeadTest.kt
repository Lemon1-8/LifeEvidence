package app.shenghuoyouju

import app.shenghuoyouju.data.GitHead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GitHeadTest {
    private val sha = "d63794d5dba477ca42b3309a5ae5b396f330c35f"

    @Test fun readsExactMainRefAfterUtf8RefAndCapabilities() {
        val advertisement = response(
            "$sha HEAD\u0000multi_ack symref=HEAD:refs/heads/main\n",
            "${"b".repeat(40)} refs/heads/中文分支\n",
            "${"c".repeat(40)} refs/heads/main-backup\n",
            "$sha refs/heads/main\n"
        )
        assertEquals(sha, GitHead.parse(advertisement))
    }

    @Test fun rejectsTruncatedPacketsAndMissingFinalFlush() {
        val valid = response("$sha refs/heads/main\n")
        for (cut in listOf(1, 3, 10, valid.size - 4, valid.size - 7)) {
            assertThrows(IllegalArgumentException::class.java) { GitHead.parse(valid.copyOf(cut)) }
        }
    }

    @Test fun rejectsMissingMainEvenWhenHeadAndSimilarBranchExist() {
        assertThrows(IllegalArgumentException::class.java) {
            GitHead.parse(response("$sha HEAD\u0000symref=HEAD:refs/heads/main\n", "$sha refs/heads/main-backup\n"))
        }
    }

    @Test fun rejectsInvalidShaAndDuplicateMain() {
        assertThrows(IllegalArgumentException::class.java) {
            GitHead.parse(response("${"z".repeat(40)} refs/heads/main\n"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            GitHead.parse(response("$sha refs/heads/main\n", "$sha refs/heads/main\n"))
        }
    }

    @Test fun rejectsWrongServiceBadLengthAndTrailingData() {
        val invalid = listOf(
            packet("# service=git-receive-pack\n") + "00000000".toByteArray(),
            "zzzz".toByteArray(),
            response("$sha refs/heads/main\n") + "extra".toByteArray()
        )
        invalid.forEach { bytes -> assertThrows(IllegalArgumentException::class.java) { GitHead.parse(bytes) } }
    }

    private fun response(vararg records: String): ByteArray =
        packet("# service=git-upload-pack\n") + "0000".toByteArray() +
            records.fold(ByteArray(0)) { bytes, record -> bytes + packet(record) } + "0000".toByteArray()

    private fun packet(text: String): ByteArray {
        val payload = text.toByteArray(Charsets.UTF_8)
        return (payload.size + 4).toString(16).padStart(4, '0').toByteArray(Charsets.US_ASCII) + payload
    }
}
