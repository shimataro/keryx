package works.merc.keryx.app.domain

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.core.CloudStorageException
import works.merc.keryx.app.core.MAX_SYNC_DB_SIZE_BYTES
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.core.SYNC_POINTER_PATH
import works.merc.keryx.app.core.SyncConflictException
import works.merc.keryx.app.data.cloud.CloudFileEntry
import works.merc.keryx.app.data.cloud.CloudFileMeta
import works.merc.keryx.app.data.cloud.CloudStorage
import works.merc.keryx.app.data.local.FtsManager
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.fileDb
import works.merc.keryx.app.platform.Gzip
import works.merc.keryx.app.platform.TransportConnections
import works.merc.keryx.app.platform.TransportFiles
import works.merc.keryx.app.platform.TransportOpenMode
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * A hand-rolled [CloudStorage] over an in-memory map of basenames, modelling what the delta sync has
 * to tolerate from real providers: an eventually consistent listing (a new file is missing from the
 * next [listingDelay] listings, a deleted one keeps being listed for [ghostListings] more),
 * duplicate names in a listing ([duplicateNames], as Google Drive can return), a 404 for a missing
 * file, a create-only write that refuses an existing name, and a revision-checked replace. One-shot
 * queued results let a test force any failure.
 */
internal class FakeCloudStorage : CloudStorage {
    class StoredFile(val bytes: ByteArray, val rev: String, val visibleFromListing: Long)

    val files = linkedMapOf<String, StoredFile>()
    private val ghosts = mutableMapOf<String, Pair<StoredFile, Long>>()
    private var revCounter = 0

    var listings = 0L
        private set

    /** How many listings right after a write still miss the new file (0: the next listing shows it). */
    var listingDelay: () -> Long = { 0L }

    /** Further listings a deleted file is still listed for. */
    var ghostListings: () -> Long = { 0L }

    /** When true, every listed file is listed twice. */
    var duplicateNames = false

    var metadataCount = 0
    var pointerChecks = 0
    var downloadCount = 0
    var createCount = 0
    var uploadCount = 0
    var deleteCount = 0
    var listCount = 0
    var downloadedBytes = 0L
    var uploadedBytes = 0L

    /** Every name written, in order. */
    val written = mutableListOf<String>()

    /** Runs at the start of every [metadata] call. */
    var metadataHook: (suspend (String) -> Unit)? = null

    /** When set, [download] suspends on it first. */
    var downloadGate: CompletableDeferred<Unit>? = null

    /** Runs at the start of every [download] call, with the basename. */
    var downloadHook: (suspend (String) -> Unit)? = null

    private val metadataQueue = ArrayDeque<Result<CloudFileMeta?>>()
    private val downloadQueue = ArrayDeque<Result<CloudFileMeta>>()
    private val createQueue = ArrayDeque<Result<CloudFileMeta>>()
    private val uploadQueue = ArrayDeque<Result<CloudFileMeta>>()
    private val listQueue = ArrayDeque<Result<List<CloudFileEntry>>>()
    private val deleteQueue = ArrayDeque<Result<Unit>>()

    fun queueMetadata(r: Result<CloudFileMeta?>) = metadataQueue.addLast(r)
    fun queueDownload(r: Result<CloudFileMeta>) = downloadQueue.addLast(r)
    fun queueCreate(r: Result<CloudFileMeta>) = createQueue.addLast(r)
    fun queueUpload(r: Result<CloudFileMeta>) = uploadQueue.addLast(r)
    fun queueList(r: Result<List<CloudFileEntry>>) = listQueue.addLast(r)
    fun queueDelete(r: Result<Unit>) = deleteQueue.addLast(r)

    fun clearQueues() {
        metadataQueue.clear(); downloadQueue.clear(); createQueue.clear(); uploadQueue.clear(); listQueue.clear(); deleteQueue.clear()
    }

    private fun base(path: String) = path.substringAfterLast('/')

    /** Stores [bytes] as [name], visible to the next listing. */
    fun put(name: String, bytes: ByteArray, rev: String = "seeded-${++revCounter}") {
        files[base(name)] = StoredFile(bytes, rev, listings)
    }

    fun bytesOf(name: String): ByteArray? = files[base(name)]?.bytes

    fun names(): Set<String> = files.keys.toSet()

    fun transportNames(): List<TransportFileName> = files.keys.mapNotNull(TransportFileName::parse)

    override suspend fun authenticate(): Result<Unit> = Result.Ok(Unit)

    override suspend fun metadata(path: String): Result<CloudFileMeta?> {
        metadataCount++
        if (path == SYNC_POINTER_PATH) pointerChecks++
        metadataHook?.invoke(path)
        metadataQueue.removeFirstOrNull()?.let { return it }
        return Result.Ok(files[base(path)]?.let { CloudFileMeta(it.rev) })
    }

    override suspend fun download(path: String, destPath: String): Result<CloudFileMeta> {
        downloadCount++
        downloadGate?.await()
        downloadHook?.invoke(base(path))
        downloadQueue.removeFirstOrNull()?.let { return it }
        val f = files[base(path)] ?: return Result.Err(CloudStorageException("HTTP 404: not found: $path"))
        downloadedBytes += f.bytes.size
        File(destPath).writeBytes(f.bytes)
        return Result.Ok(CloudFileMeta(f.rev))
    }

    private fun store(path: String, data: ByteArray): CloudFileMeta {
        val rev = "r${++revCounter}"
        // list() counts itself before filtering, so the next listing is number listings + 1.
        files[base(path)] = StoredFile(data, rev, listings + listingDelay() + 1)
        ghosts.remove(base(path))
        uploadedBytes += data.size
        written += base(path)
        return CloudFileMeta(rev)
    }

    override suspend fun upload(path: String, sourcePath: String, expectedRev: String?): Result<CloudFileMeta> {
        val data = File(sourcePath).readBytes()
        uploadCount++
        uploadQueue.removeFirstOrNull()?.let { return it }
        val current = files[base(path)]
        if (expectedRev != null && current?.rev != expectedRev) return Result.Err(SyncConflictException())
        return Result.Ok(store(path, data))
    }

    override suspend fun create(path: String, sourcePath: String): Result<CloudFileMeta> {
        val data = File(sourcePath).readBytes()
        createCount++
        createQueue.removeFirstOrNull()?.let { return it }
        if (files.containsKey(base(path))) return Result.Err(SyncConflictException())
        return Result.Ok(store(path, data))
    }

    override suspend fun delete(path: String): Result<Unit> {
        deleteCount++
        deleteQueue.removeFirstOrNull()?.let { return it }
        files.remove(base(path))?.let { ghosts[base(path)] = it to listings + ghostListings() }
        return Result.Ok(Unit)
    }

    override suspend fun rename(from: String, to: String): Result<Unit> {
        val f = files.remove(base(from)) ?: return Result.Ok(Unit)
        if (files.containsKey(base(to))) {
            files[base(from)] = f
            return Result.Err(CloudStorageException("destination exists: $to"))
        }
        files[base(to)] = f
        return Result.Ok(Unit)
    }

    override suspend fun list(prefix: String): Result<List<CloudFileEntry>> {
        listCount++
        listings++
        listQueue.removeFirstOrNull()?.let { return it }
        val visible = files.filter { (_, f) -> f.visibleFromListing <= listings }.map { (name, f) -> CloudFileEntry(name, f.rev, f.bytes.size.toLong()) }
        val ghostEntries = ghosts.filter { (_, g) -> g.second >= listings }.map { (name, g) -> CloudFileEntry(name, g.first.rev, g.first.bytes.size.toLong()) }
        ghosts.entries.removeAll { it.value.second < listings }
        val all = (visible + ghostEntries).filter { it.name.startsWith(prefix) }
        return Result.Ok(if (duplicateNames) all.flatMap { listOf(it, it.copy(rev = it.rev + "-dup")) } else all)
    }
}

/** Deterministic lowercase-UUID ids for generations and devices. */
@OptIn(ExperimentalUuidApi::class)
internal fun idSource(random: Random): () -> String = { Uuid.fromByteArray(random.nextBytes(16)).toString() }

/**
 * One device of a sync test: its own file database, search index, temp and data directories, and a
 * [SyncRepository] over the shared [cloud]. [restart] replaces the repository as a process restart
 * would, keeping the database and the data directory.
 */
internal class SyncDevice(
    val label: String,
    val cloud: CloudStorage,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val newId: () -> String,
    val notificationCenter: NotificationCenter = NotificationCenter(),
    private val compactionAllowed: () -> Boolean = { true },
    private val automaticSyncAllowedFlow: () -> Flow<Boolean> = { flowOf(true) },
    existingDbFile: File? = null,
) : AutoCloseable {
    val file: File
    val driver: SqlDriver
    val db: KeryxDatabase
    val fts: FtsManager
    val tempDir: File = Files.createTempDirectory("keryx-sync-$label-tmp").toFile()
    val dataDir: File = Files.createTempDirectory("keryx-sync-$label-data").toFile()
    var repo: SyncRepository
        private set
    var activityCenter = ActivityCenter()
        private set

    init {
        if (existingDbFile == null) {
            val (f, d, database) = fileDb()
            file = f; driver = d; db = database
        } else {
            file = existingDbFile
            driver = app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver(
                "jdbc:sqlite:${existingDbFile.absolutePath}",
                works.merc.keryx.app.data.local.sqliteConnectionProperties().apply { remove("foreign_keys") },
            )
            db = KeryxDatabase(driver)
        }
        fts = FtsManager(driver)
        runBlocking { fts.ensureIndexed() }
        repo = newRepo()
    }

    private fun newRepo(): SyncRepository {
        activityCenter = ActivityCenter()
        return SyncRepository(
            driver = driver,
            db = db,
            ftsManager = fts,
            cloudProvider = { cloud },
            clock = clock,
            scope = scope,
            activityCenter = activityCenter,
            notificationCenter = notificationCenter,
            localDbPath = file.absolutePath,
            tempDir = tempDir.absolutePath,
            dataDir = dataDir.absolutePath,
            automaticSyncAllowedFlow = automaticSyncAllowedFlow,
            compactionAllowedFlow = { flowOf(compactionAllowed()) },
            newId = newId,
        )
    }

    /** A new [SyncRepository] over the same database, as after the process restarted. */
    fun restart() {
        repo = newRepo()
    }

    fun state(key: String): String? = db.sync_stateQueries.get(key).executeAsOneOrNull()

    fun outbox() = db.sync_bookkeepingQueries.outboxAll().executeAsList()

    fun capture() = db.sync_bookkeepingQueries.captureState().executeAsOne()

    override fun close() {
        driver.close()
        tempDir.deleteRecursively()
        dataDir.deleteRecursively()
        file.delete()
    }
}

/** Builders for cloud files a test plants directly. */
internal object SyncFiles {
    fun sha256Hex(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    fun gzip(plain: File): ByteArray {
        val gz = File.createTempFile("keryx-gz-", ".gz")
        try {
            Gzip.compressFile(plain.absolutePath, gz.absolutePath)
            return gz.readBytes()
        } finally {
            gz.delete()
        }
    }

    fun gunzip(bytes: ByteArray): File {
        val gz = File.createTempFile("keryx-gunzip-", ".gz").apply { writeBytes(bytes) }
        val plain = File.createTempFile("keryx-gunzip-", ".db")
        Gzip.decompressFile(gz.absolutePath, plain.absolutePath, MAX_SYNC_DB_SIZE_BYTES)
        gz.delete()
        return plain
    }

    /**
     * A transport file for [meta] with [statements] run on it (rows, a different `user_version`, …),
     * named by the hash of its final content. Returns the name and the gzip bytes.
     */
    fun transportFile(meta: TransportMeta, vararg statements: String): Pair<TransportFileName, ByteArray> {
        val plain = File.createTempFile("keryx-planted-", ".db")
        try {
            TransportFiles.createEmpty(plain.absolutePath, meta)
            TransportConnections.open(plain.absolutePath, TransportOpenMode.READ_WRITE).use { c -> statements.forEach(c::exec) }
            val name = meta.fileName(sha256Hex(plain).take(12))
            return name to gzip(plain)
        } finally {
            plain.delete()
        }
    }

    /** Rows of [sql] on the transport file in [gzBytes]. */
    fun query(gzBytes: ByteArray, sql: String): List<List<String?>> {
        val plain = gunzip(gzBytes)
        try {
            return TransportConnections.open(plain.absolutePath, TransportOpenMode.READ_ONLY).use { it.queryRows(sql) }
        } finally {
            plain.delete()
        }
    }
}
