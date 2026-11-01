package app.mapa.core.engine

import app.mapa.routine.BinObservation
import app.mapa.routine.LifeTag
import app.mapa.routine.LifeTagKind
import java.io.File

/** One thing that happened to the engine, in order. Replaying the journal rebuilds the engine exactly. */
sealed interface JournalEntry {
    data class Start(val installT: Int, val model: String) : JournalEntry
    data class Bin(val obs: BinObservation, val paused: Boolean) : JournalEntry
    data class Tag(val id: String, val tag: LifeTag) : JournalEntry
    data class Answer(val yes: Boolean) : JournalEntry
}

/** Plain-text line codec: small, append-only, readable when debugging a phone's log. */
object JournalCodec {
    fun encode(e: JournalEntry): String = when (e) {
        is JournalEntry.Start -> "s ${e.installT} ${e.model}"
        // 7th field: moved (1 moved, 0 listening and still, _ no movement signal). Older lines have 6 fields.
        is JournalEntry.Bin -> "b ${e.obs.t} ${e.obs.interactions} ${if (e.obs.charging) 1 else 0} ${e.obs.battery} ${if (e.paused) 1 else 0} " +
            when (e.obs.moved) { true -> "1"; false -> "0"; null -> "_" }
        is JournalEntry.Tag -> "g ${e.id} ${e.tag.startT} ${e.tag.endT} ${e.tag.kind.name}"
        is JournalEntry.Answer -> "a ${if (e.yes) 1 else 0}"
    }

    fun decode(line: String): JournalEntry? {
        val p = line.trim().split(' ')
        return runCatching {
            when (p[0]) {
                "s" -> JournalEntry.Start(p[1].toInt(), p.getOrElse(2) { "unknown" })
                "b" -> JournalEntry.Bin(
                    BinObservation(p[1].toInt(), p[2].toInt(), p[3] == "1", p[4].toInt(), when (p.getOrNull(6)) { "1" -> true; "0" -> false; else -> null }),
                    p[5] == "1",
                )
                "g" -> JournalEntry.Tag(p[1], LifeTag(p[2].toInt(), p[3].toInt(), LifeTagKind.valueOf(p[4])))
                "a" -> JournalEntry.Answer(p[1] == "1")
                else -> null
            }
        }.getOrNull()
    }
}

interface JournalStore {
    fun read(): List<JournalEntry>
    fun append(entries: List<JournalEntry>)
    fun clear()
}

class FileJournalStore(private val file: File) : JournalStore {
    override fun read(): List<JournalEntry> =
        if (!file.exists()) emptyList() else file.readLines().mapNotNull { JournalCodec.decode(it) }

    override fun append(entries: List<JournalEntry>) {
        if (entries.isEmpty()) return
        file.parentFile?.mkdirs()
        file.appendText(entries.joinToString("") { JournalCodec.encode(it) + "\n" })
    }

    override fun clear() {
        file.delete()
    }
}

class MemoryJournalStore : JournalStore {
    val entries = mutableListOf<JournalEntry>()
    override fun read() = entries.toList()
    override fun append(entries: List<JournalEntry>) {
        this.entries += entries
    }
    override fun clear() = entries.clear()
}
