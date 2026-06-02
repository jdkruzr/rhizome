package io.rhizome.example

import io.rhizome.core.SyncConfig
import io.rhizome.core.SyncEngine
import io.rhizome.core.SyncResult
import io.rhizome.http.HttpUrlTransport
import io.rhizome.sqlite.SqliteStorageAdapter
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

/**
 * A tiny CLI that syncs the toy `note`+`tag` schema through a RhizomeSync relay — the client half
 * of examples/run-demo. Each invocation opens its SQLite file, makes one local change (or none),
 * and runs ONE sync round, so two instances pointed at the same server converge. Sync/transport
 * logs go to stderr; command results go to stdout (so run-demo can read `list` cleanly).
 *
 * Usage:
 *   --db <path>     SQLite file for this client (required)
 *   --server <url>  relay base URL          (default http://localhost:8080)
 *   --user/--pass   Basic credentials       (default demo/demo)
 *
 *   create --title T [--body B]      create a note, then sync
 *   edit   --id ID [--title T] [--body B]   edit a note, then sync
 *   delete --id ID                   soft-delete a note (tombstone), then sync
 *   tag    --note ID --label L       tag a note, then sync
 *   sync                             pull/push only (no local change)
 *   list                             print this client's live notes (local read)
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) usageAndExit()
    val (opts, positionals) = parseArgs(args.toList())
    val command = positionals.firstOrNull() ?: usageAndExit()

    val dbPath = opts["db"] ?: missing("db")
    val server = opts["server"] ?: "http://localhost:8080"
    val user = opts["user"] ?: "demo"
    val pass = opts["pass"] ?: "demo"

    val handle = JdbcSqliteHandle.open(dbPath)
    TOY_DATA_DDL.forEach { handle.execute(it) }
    val registry = toyRegistry()
    val adapter = SqliteStorageAdapter(handle, registry)

    runBlocking {
        // Stable per-install site id: reuse the persisted one, else mint and enable sync once.
        val site = adapter.siteId() ?: Ulid.mint().also {
            adapter.enableSync(it)
            System.err.println("[init] minted site_id $it for $dbPath")
        }

        val config = SyncConfig.from(server, user, pass)!!
        val transport = HttpUrlTransport(config, log = { System.err.println("[http] $it") })
        val engine = SyncEngine(adapter, transport, registry.schemaHash(), log = { System.err.println("[sync] $it") })

        when (command) {
            "create" -> {
                val title = opts["title"] ?: missing("title")
                val id = Ulid.mint()
                handle.execute(
                    "INSERT INTO note (id, title, body, created_at, deleted_at) VALUES (?, ?, ?, ?, NULL)",
                    listOf(id, title, opts["body"] ?: "", System.currentTimeMillis()),
                )
                adapter.capture("note", id)
                println("created note …${id.takeLast(6)}  \"$title\"   [sync: ${summary(engine.syncOnce())}]   site=…${site.takeLast(6)}")
            }
            "edit" -> {
                val id = opts["id"] ?: missing("id")
                val sets = ArrayList<String>()
                val bind = ArrayList<Any?>()
                opts["title"]?.let { sets.add("title = ?"); bind.add(it) }
                opts["body"]?.let { sets.add("body = ?"); bind.add(it) }
                if (sets.isEmpty()) { System.err.println("edit needs --title and/or --body"); exitProcess(2) }
                bind.add(id)
                handle.execute("UPDATE note SET ${sets.joinToString(", ")} WHERE id = ?", bind)
                adapter.capture("note", id)
                println("edited note …${id.takeLast(6)}   [sync: ${summary(engine.syncOnce())}]")
            }
            "delete" -> {
                val id = opts["id"] ?: missing("id")
                handle.execute("UPDATE note SET deleted_at = ? WHERE id = ?", listOf(System.currentTimeMillis(), id))
                adapter.capture("note", id)
                println("deleted note …${id.takeLast(6)}   [sync: ${summary(engine.syncOnce())}]")
            }
            "tag" -> {
                val noteId = opts["note"] ?: missing("note")
                val label = opts["label"] ?: missing("label")
                val id = Ulid.mint()
                handle.execute(
                    "INSERT INTO tag (id, note_id, label, created_at, deleted_at) VALUES (?, ?, ?, ?, NULL)",
                    listOf(id, noteId, label, System.currentTimeMillis()),
                )
                adapter.capture("tag", id)
                println("tagged note …${noteId.takeLast(6)} \"$label\"   [sync: ${summary(engine.syncOnce())}]")
            }
            "sync" -> println("sync: ${summary(engine.syncOnce())}   cursor=${adapter.cursor()}")
            "list" -> printNotes(handle)
            // Machine-readable: full ids of live notes, one per line (used by run-demo). No sync.
            "ids" -> handle.query("SELECT id FROM note WHERE deleted_at IS NULL ORDER BY created_at, id") { it.getString("id")!! }
                .forEach { println(it) }
            else -> usageAndExit()
        }
    }
}

private fun summary(r: SyncResult): String = when (r) {
    is SyncResult.Success -> "ok"
    is SyncResult.NotEnabled -> "not-enabled"
    is SyncResult.AuthRequired -> "auth-required"
    is SyncResult.SchemaMismatch -> "schema-mismatch"
    is SyncResult.Retryable -> "retryable(${r.reason})"
    is SyncResult.Failed -> "failed(${r.reason})"
}

private fun printNotes(handle: JdbcSqliteHandle) {
    val rows = handle.query(
        "SELECT id, title, body FROM note WHERE deleted_at IS NULL ORDER BY created_at, id",
    ) { r -> Triple(r.getString("id")!!, r.getString("title") ?: "", r.getString("body") ?: "") }
    if (rows.isEmpty()) {
        println("(no live notes)")
        return
    }
    println("live notes (${rows.size}):")
    for ((id, title, body) in rows) {
        println("  …${id.takeLast(6)}  $title" + if (body.isNotEmpty()) "  — $body" else "")
    }
}

/** Parse `--key value` / bare `--flag` options and bare positionals from anywhere in the args, so
 *  the command may appear before or after the global options. */
private fun parseArgs(args: List<String>): Pair<Map<String, String>, List<String>> {
    val opts = HashMap<String, String>()
    val positionals = ArrayList<String>()
    var i = 0
    while (i < args.size) {
        val a = args[i]
        if (a.startsWith("--")) {
            val key = a.removePrefix("--")
            opts[key] = if (i + 1 < args.size && !args[i + 1].startsWith("--")) args[++i] else "true"
        } else {
            positionals.add(a)
        }
        i++
    }
    return opts to positionals
}

private fun missing(name: String): Nothing {
    System.err.println("--$name is required")
    exitProcess(2)
}

private fun usageAndExit(): Nothing {
    System.err.println(
        """
        usage: example-client-kotlin --db <path> [--server URL] [--user U] [--pass P] <command> [args]
          create --title T [--body B] | edit --id ID [--title T] [--body B] | delete --id ID
          tag --note ID --label L | sync | list | ids
        """.trimIndent(),
    )
    exitProcess(2)
}
