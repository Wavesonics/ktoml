/**
 * All representations of TOML value nodes are stored in this file
 */

package com.akuleshov7.ktoml.tree

import com.akuleshov7.ktoml.TomlConfig
import com.akuleshov7.ktoml.TomlInputConfig
import com.akuleshov7.ktoml.TomlOutputConfig
import com.akuleshov7.ktoml.exceptions.ParseException
import com.akuleshov7.ktoml.exceptions.TomlWritingException
import com.akuleshov7.ktoml.parsers.trimBrackets
import com.akuleshov7.ktoml.parsers.trimQuotes
import com.akuleshov7.ktoml.parsers.trimSingleQuotes
import com.akuleshov7.ktoml.utils.appendCodePointCompat
import com.akuleshov7.ktoml.utils.controlCharacterRegex
import com.akuleshov7.ktoml.utils.unescapedBackslashRegex
import com.akuleshov7.ktoml.writers.TomlEmitter
import kotlinx.datetime.*

/**
 * Base class for all nodes that represent values
 * @property lineNo - line number of original file
 */
public sealed class TomlValue(public val lineNo: Int) {
    public abstract var content: Any

    @Deprecated(
        message = "TomlConfig is deprecated; use TomlOutputConfig instead. Will be removed in next releases.",
        replaceWith = ReplaceWith(
            "write(emitter, config, multiline)",
            "com.akuleshov7.ktoml.TomlOutputConfig"
        )
    )
    public fun write(
        emitter: TomlEmitter,
        config: TomlConfig,
        multiline: Boolean = false
    ): Unit = write(emitter, config.output, multiline)

    /**
     * Writes this value to the specified [emitter], optionally writing the value
     * [multiline] (if supported by the value type).
     *
     * @param emitter
     * @param config
     * @param multiline
     */
    public abstract fun write(
        emitter: TomlEmitter,
        config: TomlOutputConfig = TomlOutputConfig(),
        multiline: Boolean = false
    )
}

/**
 * Toml AST Node for a representation of literal string values: key = 'value' (with single quotes and no escaped symbols)
 * The only difference from the TOML specification (https://toml.io/en/v1.0.0) is that we will have one escaped symbol -
 * single quote and so it will be possible to use a single quote inside.
 * @property content
 */
public class TomlLiteralString
internal constructor(
    override var content: Any,
    lineNo: Int
) : TomlValue(lineNo) {
    public constructor(
        content: String,
        lineNo: Int,
        config: TomlInputConfig = TomlInputConfig()
    ) : this(content.verifyAndTrimQuotes(lineNo, config), lineNo)

    @Deprecated(
        message = "TomlConfig is deprecated; use TomlInputConfig instead. Will be removed in next releases."
    )
    public constructor(
        content: String,
        lineNo: Int,
        config: TomlConfig
    ) : this(
        content,
        lineNo,
        config.input
    )

    override fun write(
        emitter: TomlEmitter,
        config: TomlOutputConfig,
        multiline: Boolean
    ) {
        if (multiline) {
            throw TomlWritingException(
                "Multiline strings are not yet supported."
            )
        }

        val content = content as String

        emitter.emitValue(
            content.escapeQuotesAndVerify(config),
            isLiteral = true,
            multiline
        )
    }

    public companion object {
        private fun String.verifyAndTrimQuotes(lineNo: Int, config: TomlInputConfig): Any =
                if (startsWith("'") && endsWith("'")) {
                    val contentString = trimSingleQuotes()
                    if (config.allowEscapedQuotesInLiteralStrings) contentString.convertSingleQuotes() else contentString
                } else {
                    throw ParseException(
                        "Literal string should be wrapped with single quotes (''), it looks that you have forgotten" +
                                " the single quote in the end of the following string: <$this>", lineNo
                    )
                }

        /**
         * According to the TOML standard (https://toml.io/en/v1.0.0#string) single quote is prohibited.
         * But in ktoml we don't see any reason why we cannot escape it. Anyway, by the TOML specification we should fail, so
         * why not to try to handle this situation at least somehow.
         *
         * Conversion is done after we have trimmed technical quotes and won't break cases when the user simply used a backslash
         * as the last symbol (single quote) will be removed.
         */
        private fun String.convertSingleQuotes(): String = this.replace("\\'", "'")

        private fun String.escapeQuotesAndVerify(config: TomlOutputConfig) =
                when {
                    controlCharacterRegex in this ->
                        throw TomlWritingException(
                            "Control characters (excluding tab) are not permitted" +
                                    " in literal strings."
                        )
                    '\\' in this ->
                        throw TomlWritingException(
                            "Escapes are not allowed in literal strings."
                        )
                    '\'' in this ->
                        if (config.allowEscapedQuotesInLiteralStrings) {
                            replace("'", "\\'")
                        } else {
                            throw TomlWritingException(
                                "Single quotes are not permitted in literal string" +
                                        " by default. Set allowEscapedQuotesInLiteral" +
                                        "Strings to true in the config to ignore this."
                            )
                        }
                    else -> this
                }
    }
}

/**
 * Toml AST Node for a representation of string values: key = "value" (always should have quotes due to TOML standard)
 * @property content
 */
public class TomlBasicString
internal constructor(
    override var content: Any,
    lineNo: Int
) : TomlValue(lineNo) {
    public constructor(
        content: String,
        lineNo: Int
    ) : this(content.verifyAndTrimQuotes(lineNo), lineNo)

    override fun write(
        emitter: TomlEmitter,
        config: TomlOutputConfig,
        multiline: Boolean
    ) {
        if (multiline) {
            throw TomlWritingException(
                "Multiline strings are not yet supported."
            )
        }

        val content = content as String

        emitter.emitValue(
            content.escapeSpecialCharacters(),
            isLiteral = false,
            multiline
        )
    }

    public companion object {
        private const val COMPLEX_UNICODE_LENGTH = 8
        private const val COMPLEX_UNICODE_PREFIX = 'U'
        private const val HEX_RADIX = 16
        private const val SIMPLE_UNICODE_LENGTH = 4
        private const val SIMPLE_UNICODE_PREFIX = 'u'

        private fun String.verifyAndTrimQuotes(lineNo: Int): Any =
                if (startsWith("\"") && endsWith("\"")) {
                    trimQuotes()
                        .checkOtherQuotesAreEscaped(lineNo)
                        .convertSpecialCharacters(lineNo)
                } else {
                    throw ParseException(
                        "According to the TOML specification string values (even Enums)" +
                                " should be wrapped (start and end) with quotes (\"\"), but the following value was not: <$this>." +
                                " Please note that multiline strings are not yet supported.",
                        lineNo
                    )
                }

        private fun String.checkOtherQuotesAreEscaped(lineNo: Int): String {
            this.forEachIndexed { index, ch ->
                if (ch == '\"' && (index == 0 || this[index - 1] != '\\')) {
                    throw ParseException(
                        "Found invalid quote that is not escaped." +
                                " Please remove the quote or use escaping" +
                                " in <$this> at position = [$index].", lineNo
                    )
                }
            }
            return this
        }

        private fun String.convertSpecialCharacters(lineNo: Int): String {
            val resultString = StringBuilder()
            var i = 0
            while (i < length) {
                val currentChar = get(i)
                var offset = 1
                if (currentChar == '\\' && i != lastIndex) {
                    // Escaped
                    val next = get(i + 1)
                    offset++
                    when (next) {
                        't' -> resultString.append('\t')
                        'b' -> resultString.append('\b')
                        'r' -> resultString.append('\r')
                        'n' -> resultString.append('\n')
                        '\\' -> resultString.append('\\')
                        '\'' -> resultString.append('\'')
                        '"' -> resultString.append('"')
                        SIMPLE_UNICODE_PREFIX, COMPLEX_UNICODE_PREFIX ->
                            offset += resultString.appendEscapedUnicode(this, next, i + 2, lineNo)
                        else -> throw ParseException(
                            "According to TOML documentation unknown" +
                                    " escape symbols are not allowed. Please check: [\\$next]",
                            lineNo
                        )
                    }
                } else {
                    resultString.append(currentChar)
                }
                i += offset
            }
            return resultString.toString()
        }

        private fun StringBuilder.appendEscapedUnicode(
            fullString: String,
            marker: Char,
            codeStartIndex: Int,
            lineNo: Int
        ): Int {
            val nbUnicodeChars = if (marker == SIMPLE_UNICODE_PREFIX) {
                SIMPLE_UNICODE_LENGTH
            } else {
                COMPLEX_UNICODE_LENGTH
            }
            if (codeStartIndex + nbUnicodeChars > fullString.length) {
                val invalid = fullString.substring(codeStartIndex - 1)
                throw ParseException(
                    "According to TOML documentation unknown" +
                            " escape symbols are not allowed. Please check: [\\$invalid]",
                    lineNo
                )
            }
            val hexCode = fullString.substring(codeStartIndex, codeStartIndex + nbUnicodeChars)
            val codePoint = hexCode.toInt(HEX_RADIX)
            try {
                appendCodePointCompat(codePoint)
            } catch (e: IllegalArgumentException) {
                throw ParseException(
                    "According to TOML documentation unknown" +
                            " escape symbols are not allowed. Please check: [\\$marker$hexCode]",
                    lineNo
                )
            }
            return nbUnicodeChars
        }

        private fun String.escapeSpecialCharacters(): String {
            val withCtrlCharsEscaped = replace(controlCharacterRegex) { match ->
                when (val char = match.value.single()) {
                    '\b' -> "\\b"
                    '\n' -> "\\n"
                    '\u000C' -> "\\f"
                    '\r' -> "\\r"
                    else -> {
                        val code = char.code

                        val hexDigits = code.toString(HEX_RADIX)

                        "\\$SIMPLE_UNICODE_PREFIX${
                            hexDigits.padStart(SIMPLE_UNICODE_LENGTH, '0')
                        }"
                    }
                }
            }

            return withCtrlCharsEscaped.replace(
                unescapedBackslashRegex,
                Regex.escapeReplacement("\\\\")
            )
        }
    }
}

/**
 * Toml AST Node for a representation of Arbitrary 64-bit signed integers: key = 1
 * @property content
 */
public class TomlLong
internal constructor(
    override var content: Any,
    lineNo: Int
) : TomlValue(lineNo) {
    public constructor(content: String, lineNo: Int) : this(content.toLong(), lineNo)

    override fun write(
        emitter: TomlEmitter,
        config: TomlOutputConfig,
        multiline: Boolean
    ) {
        emitter.emitValue(content as Long)
    }
}

/**
 * Toml AST Node for a representation of float types: key = 1.01.
 * Toml specification requires floating point numbers to be IEEE 754 binary64 values,
 * so it should be Kotlin Double (64 bits)
 * @property content
 */
public class TomlDouble
internal constructor(
    override var content: Any,
    lineNo: Int
) : TomlValue(lineNo) {
    public constructor(content: String, lineNo: Int) : this(content.toDouble(), lineNo)

    override fun write(
        emitter: TomlEmitter,
        config: TomlOutputConfig,
        multiline: Boolean
    ) {
        emitter.emitValue(content as Double)
    }
}

/**
 * Toml AST Node for a representation of boolean types: key = true | false
 * @property content
 */
public class TomlBoolean
internal constructor(
    override var content: Any,
    lineNo: Int
) : TomlValue(lineNo) {
    public constructor(content: String, lineNo: Int) : this(content.toBoolean(), lineNo)

    override fun write(
        emitter: TomlEmitter,
        config: TomlOutputConfig,
        multiline: Boolean
    ) {
        emitter.emitValue(content as Boolean)
    }
}

/**
 * Toml AST Node for a representation of date-time types (offset date-time, local date-time, local date)
 * @property content
 */
public class TomlDateTime
internal constructor(
    override var content: Any,
    lineNo: Int
) : TomlValue(lineNo) {
    public constructor(content: String, lineNo: Int) : this(content.trim().parseToDateTime(), lineNo)

    override fun write(
        emitter: TomlEmitter,
        config: TomlOutputConfig,
        multiline: Boolean
    ) {
        when (val content = content) {
            is Instant -> emitter.emitValue(content)
            is LocalDateTime -> emitter.emitValue(content)
            is LocalDate -> emitter.emitValue(content)
            else ->
                throw TomlWritingException(
                    "Unknown date type ${content::class.simpleName}"
                )
        }
    }

    public companion object {
        private fun String.parseToDateTime(): Any = try {
            // Offset date-time
            toInstant()
        } catch (e: IllegalArgumentException) {
            try {
                // TOML spec allows a space instead of the T, try replacing the first space by a T
                replaceFirst(' ', 'T').toInstant()
            } catch (e: IllegalArgumentException) {
                try {
                    // Local date-time
                    toLocalDateTime()
                } catch (e: IllegalArgumentException) {
                    // Local date
                    toLocalDate()
                }
            }
        }
    }
}

/**
 * Toml AST Node for a representation of null:
 * null, nil, NULL, NIL or empty (key = )
 */
public class TomlNull(lineNo: Int) : TomlValue(lineNo) {
    override var content: Any = "null"

    override fun write(
        emitter: TomlEmitter,
        config: TomlOutputConfig,
        multiline: Boolean
    ) {
        emitter.emitNullValue()
    }
}

/**
 * Toml AST Node for a representation of arrays: key = [value1, value2, value3]
 * @property content
 */
public class TomlArray
internal constructor(
    override var content: Any,
    private val rawContent: String,
    lineNo: Int
) : TomlValue(lineNo) {
    public constructor(
        rawContent: String,
        lineNo: Int,
        config: TomlInputConfig = TomlInputConfig()
    ) : this(
        rawContent.parse(lineNo, config),
        rawContent,
        lineNo
    ) {
        validateQuotes()
    }

    @Deprecated(
        message = "TomlConfig is deprecated; use TomlInputConfig instead. Will be removed in next releases."
    )
    public constructor(
        rawContent: String,
        lineNo: Int,
        config: TomlConfig
    ) : this(
        rawContent,
        lineNo,
        config.input
    )

    @Deprecated(
        message = "TomlConfig is deprecated; use TomlInputConfig instead. Will be removed in next releases.",
        replaceWith = ReplaceWith(
            "parse(config)",
            "com.akuleshov7.ktoml.TomlInputConfig"
        )
    )
    public fun parse(config: TomlConfig): List<Any> = parse(config.input)

    /**
     * small adaptor to make proper testing of parsing
     *
     * @param config
     * @return converted array to a list
     */
    public fun parse(config: TomlInputConfig = TomlInputConfig()): List<Any> = rawContent.parse(lineNo, config)

    /**
     * small validation for quotes: each quote should be closed in a key
     */
    private fun validateQuotes() {
        if (rawContent.count { it == '\"' } % 2 != 0 || rawContent.count { it == '\'' } % 2 != 0) {
            throw ParseException(
                "Not able to parse the key: [$rawContent] as it does not have closing quote",
                lineNo
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    public override fun write(
        emitter: TomlEmitter,
        config: TomlOutputConfig,
        multiline: Boolean
    ) {
        emitter.startArray()

        val content = (content as List<Any>).map {
            if (it is List<*>) {
                TomlArray(it, "", 0)
            } else {
                it as TomlValue
            }
        }

        val last = content.lastIndex

        if (multiline) {
            emitter.indent()

            content.forEachIndexed { i, value ->
                emitter.emitNewLine()
                    .emitIndent()

                value.write(emitter, config, multiline = value is TomlArray)

                if (i < last) {
                    emitter.emitElementDelimiter()
                }
            }

            emitter.dedent()
            emitter.emitNewLine()
                .emitIndent()
        } else {
            content.forEachIndexed { i, value ->
                emitter.emitWhitespace()

                value.write(emitter, config)

                if (i < last) {
                    emitter.emitElementDelimiter()
                }
            }

            emitter.emitWhitespace()
        }

        emitter.endArray()
    }

    public companion object {
        /**
         * recursively parse TOML array from the string: [ParsingArray -> Trimming values -> Parsing Nested Arrays]
         */
        private fun String.parse(lineNo: Int, config: TomlInputConfig = TomlInputConfig()): List<Any> =
                this.parseArray()
                    .map { it.trim() }
                    .map { if (it.startsWith("[")) it.parse(lineNo, config) else it.parseValue(lineNo, config) }

        /**
         * method for splitting the string to the array: "[[a, b], [c], [d]]" to -> [a,b] [c] [d]
         */
        @Suppress("NESTED_BLOCK", "TOO_LONG_FUNCTION")
        private fun String.parseArray(): MutableList<String> {
            // covering cases when the array is intentionally blank: myArray = []. It should be empty and not contain null
            if (this.trimBrackets().isBlank()) {
                return mutableListOf()
            }

            var nbBrackets = 0
            var isInBasicString = false
            var isInLiteralString = false
            var bufferBetweenCommas = StringBuilder()
            val result: MutableList<String> = mutableListOf()

            val trimmed = trimBrackets()
            for (i in trimmed.indices) {
                when (val current = trimmed[i]) {
                    '[' -> {
                        nbBrackets++
                        bufferBetweenCommas.append(current)
                    }
                    ']' -> {
                        nbBrackets--
                        bufferBetweenCommas.append(current)
                    }
                    '\'' -> {
                        if (!isInBasicString) {
                            isInLiteralString = !isInLiteralString
                        }
                        bufferBetweenCommas.append(current)
                    }
                    '"' -> {
                        if (!isInLiteralString) {
                            if (!isInBasicString) {
                                isInBasicString = true
                            } else if (trimmed[i - 1] != '\\') {
                                isInBasicString = false
                            }
                        }
                        bufferBetweenCommas.append(current)
                    }
                    // split only if we are on the highest level of brackets (all brackets are closed)
                    // and if we're not in a string
                    ',' -> if (isInBasicString || isInLiteralString || nbBrackets != 0) {
                        bufferBetweenCommas.append(current)
                    } else {
                        result.add(bufferBetweenCommas.toString())
                        bufferBetweenCommas = StringBuilder()
                    }
                    else -> bufferBetweenCommas.append(current)
                }
            }
            result.add(bufferBetweenCommas.toString())
            return result
        }
    }
}
