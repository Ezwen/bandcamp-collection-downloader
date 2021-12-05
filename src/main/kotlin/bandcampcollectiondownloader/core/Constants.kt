package bandcampcollectiondownloader.core

object Constants {

    const val LINESIZE: Int = 130
    const val VERSION: String = "v2021-12-05"
    val UNICODE_CHARS_REPLACEMENTS = hashMapOf<Char, Char>(
            ':' to '꞉',
            '/' to '／',
            '\\' to '⧹',
            '"' to '＂',
            '*' to '⋆',
            '<' to '＜',
            '>' to '＞',
            '?' to '？',
            '|' to '∣'
    )
}