package works.merc.keryx.app.core

/** The `0`/`1` INTEGER a boolean column stores (see `docs/db-schema.md`). */
internal fun Boolean.toDbFlag(): Long = if (this) 1L else 0L
