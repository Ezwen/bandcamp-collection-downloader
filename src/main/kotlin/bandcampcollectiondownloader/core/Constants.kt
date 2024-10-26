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

    // On Windows, period and space are allowed, but not at the end of a file/directory name.
    // See: https://learn.microsoft.com/en-us/windows/win32/fileio/naming-a-file
    val TRAILING_CHAR_REPLACEMENTS = hashMapOf<Char, Char>(
            '.' to '․',
            ' ' to ' ',
    )
}