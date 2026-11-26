package works.merc.keryx.app

import app.cash.sqldelight.db.SqlDriver

/**
 * The local schema as of version 2 (before `2.sqm`): every table and index the `.sq` files created
 * then, verbatim. A fixture for migration tests — `KeryxDatabase.Schema.create` only ever builds the
 * current version.
 */
val SCHEMA_V2_STATEMENTS: List<String> = listOf(
    """
    CREATE TABLE feeds (
        id            TEXT    NOT NULL PRIMARY KEY,
        url           TEXT    NOT NULL UNIQUE,
        site_url      TEXT,
        title         TEXT    NOT NULL,
        description   TEXT,
        favicon_url   TEXT,
        etag          TEXT,
        last_modified TEXT,
        error_count   INTEGER NOT NULL DEFAULT 0,
        last_error    TEXT,
        custom_title  TEXT,
        folder_id     TEXT REFERENCES folders(id),
        deleted_at    INTEGER,
        updated_at    INTEGER NOT NULL,
        created_at    INTEGER NOT NULL,
        sort_order    INTEGER NOT NULL DEFAULT 0,
        folder_updated_at INTEGER,
        sort_order_updated_at INTEGER,
        custom_title_updated_at INTEGER,
        deleted_updated_at INTEGER
    )
    """.trimIndent(),
    """
    CREATE TABLE articles (
        id            TEXT    NOT NULL PRIMARY KEY,
        feed_id       TEXT    NOT NULL,
        guid          TEXT    NOT NULL,
        url           TEXT    NOT NULL,
        title         TEXT    NOT NULL,
        summary       TEXT,
        content       TEXT,
        author        TEXT,
        published_at  INTEGER,
        thumbnail_url TEXT,
        is_read       INTEGER NOT NULL DEFAULT 0,
        read_at       INTEGER,
        is_starred    INTEGER NOT NULL DEFAULT 0,
        starred_at    INTEGER,
        cached_at     INTEGER NOT NULL,
        search_text   TEXT    NOT NULL DEFAULT '',
        updated_at    INTEGER NOT NULL,
        created_at    INTEGER NOT NULL,
        deleted_at         INTEGER,
        deleted_updated_at INTEGER,
        FOREIGN KEY (feed_id) REFERENCES feeds(id),
        UNIQUE (feed_id, guid)
    )
    """.trimIndent(),
    """
    CREATE INDEX idx_articles_feed_id    ON articles(feed_id)
    """.trimIndent(),
    """
    CREATE INDEX idx_articles_is_read    ON articles(is_read)
    """.trimIndent(),
    """
    CREATE INDEX idx_articles_is_starred ON articles(is_starred)
    """.trimIndent(),
    """
    CREATE INDEX idx_articles_published  ON articles(published_at DESC, created_at DESC, id DESC)
    """.trimIndent(),
    """
    CREATE TABLE tags (
        id         TEXT    NOT NULL PRIMARY KEY,
        name       TEXT    NOT NULL UNIQUE,
        color      TEXT,
        sort_order INTEGER NOT NULL DEFAULT 0,
        deleted_at INTEGER,
        updated_at INTEGER NOT NULL,
        created_at INTEGER NOT NULL
    )
    """.trimIndent(),
    """
    CREATE TABLE folders (
        id         TEXT    NOT NULL PRIMARY KEY,
        name       TEXT    NOT NULL UNIQUE,
        sort_order INTEGER NOT NULL DEFAULT 0,
        deleted_at INTEGER,
        updated_at INTEGER NOT NULL,
        created_at INTEGER NOT NULL
    )
    """.trimIndent(),
    """
    CREATE TABLE feed_tags (
        feed_id    TEXT    NOT NULL,
        tag_id     TEXT    NOT NULL,
        deleted_at INTEGER,
        updated_at INTEGER NOT NULL,
        PRIMARY KEY (feed_id, tag_id),
        FOREIGN KEY (feed_id) REFERENCES feeds(id),
        FOREIGN KEY (tag_id) REFERENCES tags(id)
    )
    """.trimIndent(),
    """
    CREATE TABLE global_settings (
        key        TEXT    NOT NULL PRIMARY KEY,
        value      TEXT    NOT NULL,
        updated_at INTEGER NOT NULL
    )
    """.trimIndent(),
    """
    CREATE TABLE sync_state (
        key   TEXT NOT NULL PRIMARY KEY,
        value TEXT NOT NULL
    )
    """.trimIndent(),
)

/** Builds the version-2 schema ([SCHEMA_V2_STATEMENTS]) on [this] and stamps `user_version = 2`. */
fun SqlDriver.createSchemaV2() {
    SCHEMA_V2_STATEMENTS.forEach { execute(null, it, 0) }
    execute(null, "PRAGMA user_version = 2", 0)
}
