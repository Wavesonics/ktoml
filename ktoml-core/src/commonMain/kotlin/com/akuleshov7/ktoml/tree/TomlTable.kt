/**
 * Common class for tables
 */

package com.akuleshov7.ktoml.tree

import com.akuleshov7.ktoml.TomlConfig
import com.akuleshov7.ktoml.TomlInputConfig
import com.akuleshov7.ktoml.TomlOutputConfig
import com.akuleshov7.ktoml.writers.TomlEmitter

/**
 * Abstract class to represent all types of tables: primitive/arrays/etc.
 * @property content - raw string name of the table
 * @property lineNo - line number
 * @property config - toml configuration
 * @property isSynthetic
 */
@Suppress("COMMENT_WHITE_SPACE")
public abstract class TomlTable(
    override val content: String,
    override val lineNo: Int,
    comments: List<String>,
    inlineComment: String,
    override val config: TomlInputConfig = TomlInputConfig(),
    public val isSynthetic: Boolean = false
) : TomlNode(
    content,
    lineNo,
    comments,
    inlineComment,
    config
) {
    public abstract var fullTableName: String
    public abstract var tablesList: List<String>
    public abstract val type: TableType

    @Deprecated(
        message = "TomlConfig is deprecated; use TomlInputConfig instead. Will be removed in next releases."
    )
    public constructor(
        content: String,
        lineNo: Int,
        comments: List<String> = emptyList(),
        inlineComment: String = "",
        config: TomlConfig,
        isSynthetic: Boolean = false
    ) : this(
        content,
        lineNo,
        comments,
        inlineComment,
        config.input,
        isSynthetic
    )

    override fun write(
        emitter: TomlEmitter,
        config: TomlOutputConfig,
        multiline: Boolean
    ) {
        // Todo: Option to explicitly define super tables?
        // Todo: Support dotted key-value pairs (i.e. a.b.c.d = 7)

        val key = TomlKey(fullTableName, 0)

        val firstChild = children.first()

        if (isExplicit(firstChild) && type == TableType.PRIMITIVE) {
            emitter.writeHeader(key, config)

            if (inlineComment.isNotEmpty()) {
                emitter.emitComment(inlineComment, inline = true)
            }

            if (firstChild !is TomlStubEmptyNode) {
                emitter.emitNewLine()
            }

            emitter.indent()

            emitter.writeChildren(key, children, config, multiline)

            emitter.dedent()
        } else {
            emitter.writeChildren(key, children, config, multiline)
        }
    }

    protected abstract fun TomlEmitter.writeChildren(
        headerKey: TomlKey,
        children: List<TomlNode>,
        config: TomlOutputConfig,
        multiline: Boolean
    )

    protected abstract fun TomlEmitter.writeHeader(
        headerKey: TomlKey,
        config: TomlOutputConfig
    )

    /**
     * Determines whether the table should be explicitly defined. Synthetic tables
     * are implicit except when:
     *  - the first child is a pair, and there are other children (to exclude
     *    dotted pairs)
     *  - the table is empty
     */
    private fun isExplicit(
        firstChild: TomlNode
    ) = if (!isSynthetic) {
        true
    } else {
        when (firstChild) {
            is TomlStubEmptyNode -> true
            is TomlKeyValue,
            is TomlInlineTable -> children.size > 1
            else -> false
        }
    }
}

/**
 * Special Enum that is used in a logic related to insertion of tables to AST
 */
public enum class TableType {
    ARRAY,
    PRIMITIVE,
    ;
}
